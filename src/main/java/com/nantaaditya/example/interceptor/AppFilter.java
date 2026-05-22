package com.nantaaditya.example.interceptor;

import com.nantaaditya.example.helper.DateTimeHelper;
import com.nantaaditya.example.helper.EventLogHelper;
import com.nantaaditya.example.helper.ObservationHelper;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
    context.addLowCardinalityKeyValue(KeyValue.of("error", exception.getClass().getName()));
    observation.event(Event.of("error", exception.getClass().getName()));
    observation.error(exception);
  }
}
