package com.nantaaditya.example.helper;

import com.nantaaditya.example.cache.JsltResponseMapper;
import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.response.GatewayResponse;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.MaxRetriesExceededException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;

@Log4j2
@Component
@RequiredArgsConstructor
public class GatewayHelper {
  private final JsonHelper jsonHelper;
  private final JsltResponseMapper jsltResponseMapper;

  /**
   * Converts a resilience/infrastructure exception to a structured internal-reject response.
   *
   * <p>Status mapping:
   * <ul>
   *   <li>BulkheadFull → 503 (too many concurrent calls)
   *   <li>CircuitBreaker open → 503 (upstream considered unhealthy)
   *   <li>RateLimiter → 429 (caller exceeded allowed request rate)
   *   <li>MaxRetries → 502 (upstream repeatedly failed)
   *   <li>Timeout → 504 (upstream did not respond in time)
   *   <li>Other → 503 (unexpected internal failure)
   * </ul>
   *
   * <p><b>Known behavior (R8-H1)</b>: when retries are exhausted ({@link MaxRetriesExceededException}),
   * the synthetic {@code responseStatus=502} is saved to outbound_histories — not the real upstream
   * status (e.g. 500). This is intentional: history reflects the status returned to the caller.
   * A row with {@code retry_count > 0} and {@code response_status=502} means retries were exhausted
   * on a failing upstream, not that the upstream itself returned 502.
   *
   * <p>Always returns a value (never re-throws) so that {@code onErrorResume} in
   * {@link com.nantaaditya.example.service.impl.GatewayServiceImpl#executeWithResilience} fires and history is saved for every call.
   */
  public GatewayResponse toInternalRejectResponse(OutboundSystemContext context, Throwable e) {
    if (e instanceof BulkheadFullException) {
      log.warn(AppLogMessage.message("#GATEWAY - bulkhead full for {}: {}", code(context), e.getMessage()).error(e));
      return GatewayResponse.internalReject(503, "service unavailable - bulkhead full");
    }
    if (e instanceof CallNotPermittedException) {
      log.warn(AppLogMessage.message("#GATEWAY - circuit breaker open for {}: {}", code(context), e.getMessage()).error(e));
      return GatewayResponse.internalReject(503, "service unavailable - circuit open");
    }
    if (e instanceof RequestNotPermitted) {
      log.warn(AppLogMessage.message("#GATEWAY - rate limit exceeded for {}: {}", code(context), e.getMessage()).error(e));
      return GatewayResponse.internalReject(429, "too many requests - rate limited");
    }
    if (e instanceof MaxRetriesExceededException) {
      log.warn(AppLogMessage.message("#GATEWAY - max retries exceeded for {}: {}", code(context), e.getMessage()).error(e));
      return GatewayResponse.internalReject(502, "service unavailable - max retries exceeded");
    }
    if (e instanceof TimeoutException) {
      log.warn(AppLogMessage.message("#GATEWAY - read timeout for {}: {}", code(context), e.getMessage()).error(e));
      return GatewayResponse.internalReject(504, "gateway timeout");
    }
    if (isCausedBy(e, DataBufferLimitException.class)) {
      // The upstream already answered (and may have acted on the request); the answer is just larger than
      // apps.gateway.max-response-bytes. Say so instead of reporting an opaque internal error.
      log.warn(AppLogMessage.message("#GATEWAY - upstream response too large for {}: {}", code(context), e.getMessage()).error(e));
      return GatewayResponse.internalReject(502, "bad gateway - upstream response too large");
    }

    log.error(AppLogMessage.message("#GATEWAY - unexpected error for {}: {}", code(context), e.getMessage()).error(e));
    return GatewayResponse.internalReject(503, "internal gateway error");
  }

  private static boolean isCausedBy(Throwable error, Class<? extends Throwable> type) {
    for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
      if (type.isInstance(cause)) {
        return true;
      }
    }
    return false;
  }

  public String code(OutboundSystemContext context) {
    return context.getSystem().getCode();
  }

  public void applyConfiguredHeaders(OutboundSystemContext context, HttpHeaders headers) {
    Optional.ofNullable(context.getHeaders())
        .filter(list -> !list.isEmpty())
        .ifPresent(list -> list.stream()
            .map(config -> config.getHeaders(jsonHelper))
            .filter(h -> h != null && !h.isEmpty())
            .forEach(h -> h.forEach(headers::addAll))
        );
  }

  public void applyAuthHeader(AuthCredential credential, HttpHeaders headers) {
    if (credential.isEmpty()) {
      return;
    }
    if (credential.location() == TokenInjectLocation.HEADER) {
      headers.add(credential.keyName(), credential.value());
    } else if (credential.location() == TokenInjectLocation.COOKIE) {
      headers.add("Cookie", credential.keyName() + "=" + credential.value());
    }
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> resolveRequestHeaders(OutboundSystemContext context) {
    List<OutboundHeaderConfiguration> headerConfigs = context.getHeaders();
    if (headerConfigs == null || headerConfigs.isEmpty()) {
      return null; //NOSONAR
    }
    Map<String, List<String>> merged = headerConfigs.stream()
        .map(config -> config.getHeaders(jsonHelper))
        .filter(h -> h != null && !h.isEmpty())
        .flatMap(h -> h.entrySet().stream())
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            Map.Entry::getValue,
            (a, b) -> { List<String> m = new ArrayList<>(a); m.addAll(b); return m; }));
    return merged.isEmpty() ? null : (Map<String, Object>) (Map<?, ?>) merged;
  }

  public GatewayResponse applyMapping(OutboundSystemContext context, GatewayResponse response) {
    return Optional.ofNullable(context.getResponseMapper())
        .filter(m -> !m.isPassThrough())
        .map(m -> {
          String converted = jsltResponseMapper.map(response.body(), m.getMappingScript());
          if (converted == null) {
            log.warn(AppLogMessage.message("#GATEWAY - JSLT mapping failed, falling back to original body for code={}", context.getSystem().getCode()));
            return GatewayResponse.mappedWithFallback(
                response.status(), response.headers(), response.body(),
                response.status());
          }
          return GatewayResponse.mapped(
              response.status(), response.headers(), response.body(),
              m.getMappedStatus(response.status()), converted);
        })
        .orElse(response);
  }

  public Map<String, List<String>> collectResponseHeaders(ClientResponse response) {
    return response.headers()
        .asHttpHeaders()
        .entrySet()
        .stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            Map.Entry::getValue,
            (a, b) -> {
              List<String> m = new ArrayList<>(a);
              m.addAll(b);
              return m;
            }
          )
        );
  }
}
