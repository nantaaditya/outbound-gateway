package com.nantaaditya.example.interceptor;

import com.nantaaditya.example.helper.DateTimeHelper;
import com.nantaaditya.example.helper.EventLogHelper;
import com.nantaaditya.example.helper.ObservationHelper;
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.constant.ObservationConstant;
import com.nantaaditya.example.model.dto.ContextDTO;
import com.nantaaditya.example.model.logger.AppLogMessage;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.Observation.Event;
import java.time.ZonedDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * First-in-chain WebFilter — body caching, header decoration, and Micrometer observation.
 *
 * <h3>Why body caching is required</h3>
 * <p>WebFlux request bodies are single-subscription {@link reactor.core.publisher.Flux} streams.
 * Once a subscriber reads the body, the bytes are gone — a second read (e.g. controller → service)
 * would receive an empty stream. {@link DataBufferUtils#join} materialises the entire body into a
 * single {@link org.springframework.core.io.buffer.DataBuffer}, copies it to a {@code byte[]},
 * and wraps that array in a {@link ServerHttpRequestDecorator} so every downstream subscriber
 * always reads from the same in-memory snapshot.
 *
 * <h3>Body size guard</h3>
 * <p>If the buffered body exceeds {@code app.filter.max-body-bytes} (default 1 MB / 1 048 576
 * bytes) the buffer is released immediately and HTTP 413 Payload Too Large is returned before
 * any application code sees the request.
 *
 * <h3>Header reflection whitelist</h3>
 * <p>Only the three headers in {@link #REFLECT_HEADERS} ({@code X-Request-Id},
 * {@code X-Client-Id}, {@code X-Request-Time}) are copied from the request to the response.
 * Reflecting arbitrary request headers would let callers inject unexpected response headers,
 * so the whitelist is intentionally narrow.
 *
 * <h3>Observation lifecycle</h3>
 * <p>A Micrometer {@link io.micrometer.observation.Observation} is started before the chain is
 * invoked and stopped inside {@code doFinally} — which fires on success, error, <em>and</em>
 * cancellation. This guarantees the observation is always closed regardless of how the reactive
 * pipeline terminates, preventing the "scope never closed" leak logged by
 * {@code ObservationThreadLocalAccessor}.
 */
@Log4j2
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AppFilter implements WebFilter {

  private static final Set<String> REFLECT_HEADERS = Set.of(
      HeaderConstant.REQUEST_ID.getHeader(),
      HeaderConstant.CLIENT_ID.getHeader(),
      HeaderConstant.REQUEST_TIME.getHeader()
  );

  // These methods carry no body by definition — skip DataBufferUtils.join entirely.
  // Allocating a 0-byte DataBuffer via switchIfEmpty for every GET/DELETE is wasteful
  // and can cause Netty async-cleanup noise when error responses are committed before the
  // flatMap pipeline completes (observed as "Async resource cleanup failed after onComplete").
  private static final Set<HttpMethod> BODYLESS_METHODS = Set.of(
      HttpMethod.GET, HttpMethod.DELETE, HttpMethod.HEAD, HttpMethod.OPTIONS
  );

  @Value("${spring.webflux.base-path}")
  private String contextPath;

  @Value("${app.filter.max-body-bytes:1048576}")
  private int maxBodyBytes;

  private final EventLogHelper eventLogHelper;
  private final ObservationHelper observationHelper;

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();
    ServerHttpResponse response = exchange.getResponse();
    ContextDTO contextDTO = decorateContext(request);

    observationHelper.getContextHelper().put(contextDTO);
    decorateResponseHeaders(request, response);
    decorateBaggage(contextDTO);

    Observation.Context observationContext = observationHelper.createApiContext(contextDTO);
    Observation observation = Observation.start(
        ObservationConstant.from(contextDTO.getPath()).getName(),
        () -> observationContext,
        observationHelper.getObservationRegistry()
    );

    if (BODYLESS_METHODS.contains(request.getMethod())) {
      exchange.getAttributes().put("cachedRequestBody", new byte[0]);
      return chain.filter(exchange)
          .doOnError(error -> doObservationOnError(error, observationContext, observation))
          .doFinally(signal -> {
            eventLogHelper.save(exchange, contextDTO);
            try {
              observation.stop();
            } catch (Exception e) {
              log.warn(AppLogMessage.message("#Observation - failed to stop observation: {}", e.getMessage()).error(e));
            }
          })
          .contextWrite(ctx -> ctx.put("context", contextDTO));
    }

    return DataBufferUtils.join(request.getBody())
        .cast(DataBuffer.class)
        .switchIfEmpty(Mono.fromCallable(() ->
            exchange.getResponse().bufferFactory().allocateBuffer(0)
        ))
        .flatMap(dataBuffer -> {
          if (dataBuffer.readableByteCount() > maxBodyBytes) {
            DataBufferUtils.release(dataBuffer);
            return Mono.error(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds limit"));
          }
          byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
          setCachedAttribute(exchange, dataBuffer, bodyBytes);
          Flux<DataBuffer> cachedBody = getDataBufferFlux(exchange, bodyBytes);
          ServerHttpRequest mutatedRequest = cachedServerHttpRequest(request, cachedBody);
          ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

          return chain.filter(mutatedExchange)
              .doOnError(error -> doObservationOnError(error, observationContext, observation))
              .doFinally(signal -> {
                eventLogHelper.save(mutatedExchange, contextDTO);
                try {
                  observation.stop();
                } catch (Exception e) {
                  log.warn(AppLogMessage.message("#Observation - failed to stop observation: {}", e.getMessage()).error(e));
                }
              });
        })
        .contextWrite(ctx -> ctx.put("context", contextDTO));
  }

  private void decorateBaggage(ContextDTO contextDTO) {
    observationHelper.getTracerHelper().setBaggage(HeaderConstant.REQUEST_ID.getHeader(), contextDTO.getRequestId());
    observationHelper.getTracerHelper().setBaggage(HeaderConstant.CLIENT_ID.getHeader(), contextDTO.getClientId());
  }

  private void decorateResponseHeaders(ServerHttpRequest request, ServerHttpResponse response) {
    REFLECT_HEADERS.forEach(name -> {
      String value = request.getHeaders().getFirst(name);
      if (value != null) {
        response.getHeaders().add(name, value);
      }
    });
    response.getHeaders().add(HeaderConstant.RECEIVED_TIME.getHeader(),
        DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT));
  }

  private Flux<DataBuffer> getDataBufferFlux(ServerWebExchange exchange, byte[] bodyBytes) {
    return Flux.defer(() -> {
      DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bodyBytes);
      return Mono.just(buffer);
    });
  }

  private void setCachedAttribute(ServerWebExchange exchange, DataBuffer dataBuffer, byte[] bodyBytes) {
    if (dataBuffer.readableByteCount() > 0) {
      dataBuffer.read(bodyBytes);
    }
    DataBufferUtils.release(dataBuffer);
    exchange.getAttributes().put("cachedRequestBody", bodyBytes);
  }

  private ContextDTO decorateContext(ServerHttpRequest request) {
    ContextDTO contextDTO = new ContextDTO();
    contextDTO.decorateContext(request, contextPath);
    return contextDTO;
  }

  private ServerHttpRequest cachedServerHttpRequest(ServerHttpRequest request,
      Flux<DataBuffer> cachedBody) {
    return new ServerHttpRequestDecorator(request) {
      @Override
      public Flux<DataBuffer> getBody() {
        return cachedBody;
      }
    };
  }

  private <C extends Observation.Context> void doObservationOnError(Throwable exception, C context,
      Observation observation) {
    log.error(AppLogMessage.message("#Observation - error {}", exception.getMessage()).error(exception));
    context.addLowCardinalityKeyValue(KeyValue.of(ConstantName.ERROR_TAG_OBSERVABILITY, exception.getClass().getName()));
    observation.event(Event.of(ConstantName.ERROR_TAG_OBSERVABILITY, exception.getClass().getName()));
    observation.error(exception);
  }
}
