package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.factory.AuthStrategyFactory;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.helper.JsltResponseMapper;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.helper.OutboundMetricsRecorder;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.exception.OutboundSystemNotFoundException;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.response.GatewayResponse;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHeaderConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResponseMapperConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.GatewayService;
import io.micrometer.observation.Observation;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.MaxRetriesExceededException;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Central orchestrator for all outbound gateway calls.
 *
 * <h3>Execution pipeline (per call)</h3>
 * <ol>
 *   <li>{@link #loadContext} — resolves {@link OutboundSystemContext} from the two-layer cache
 *       (hot {@code contextCache} → cold {@code contextLoadCache} with {@code Mono.cache()}
 *       thundering-herd protection).</li>
 *   <li>Idempotency check — if {@code X-Idempotency-Key} is present, queries history within
 *       the configured TTL window using the composite index
 *       {@code (outbound_system_id, idempotency_key, created_date)}.</li>
 *   <li>{@link #executeWithResilience} — runs auth, HTTP call, and the resilience pipeline.</li>
 *   <li>{@link #applyMapping} — JSLT transform or pass-through.</li>
 *   <li>{@link #saveHistory} — fire-and-forget; uses upsert when an idempotency key is present
 *       so a successful retry always overwrites an earlier failure record.</li>
 * </ol>
 *
 * <h3>Resilience operator order</h3>
 * <p>{@code transformDeferred} builds the chain inside-out. The order below results in Retry
 * being the outermost wrapper at execution time:
 * <pre>  RateLimiter → Bulkhead → CircuitBreaker → Retry (outermost)</pre>
 * Retry must be outermost so each retry re-evaluates CB and Bulkhead. Placing Retry inside
 * CB would bypass the circuit breaker on retries — defeating its purpose.
 *
 * <h3>History save — fire-and-forget</h3>
 * <p>{@link #saveHistory} failures are logged and swallowed. A history write failure must
 * never surface as a gateway call failure to the caller.
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class GatewayServiceImpl implements GatewayService {

  private final OutboundSystemRepository systemRepository;
  private final OutboundHostConfigurationRepository hostConfigRepository;
  private final OutboundResilienceConfigurationRepository resilienceRepository;
  private final OutboundHeaderConfigurationRepository headerRepository;
  private final OutboundAuthConfigurationRepository authRepository;
  private final OutboundResponseMapperConfigurationRepository mapperRepository;
  private final OutboundHistoryRepository historyRepository;
  private final GatewayWebClientBuilder contextBuilder;
  private final AuthStrategyFactory authStrategyFactory;
  private final EncryptionHelper encryptionHelper;
  private final JsltResponseMapper jsltResponseMapper;
  private final JsonHelper jsonHelper;
  private final OutboundMetricsRecorder metricsRecorder;

  private final ConcurrentMap<String, OutboundSystemContext> contextCache = new ConcurrentHashMap<>();

  // Thundering-herd protection: computeIfAbsent + Mono.cache() ensures only ONE DB load
  // fires per code even when many requests arrive concurrently before the first load completes.
  // doFinally removes the entry so the next miss after eviction triggers a fresh load.
  private final ConcurrentMap<String, Mono<OutboundSystemContext>> contextLoadCache = new ConcurrentHashMap<>();

  @Override
  public Mono<GatewayResponse> execute(
      String code, String callerService, String idempotencyKey, GatewayRequest request) {
    return loadContext(code)
        .flatMap(context -> {
          if (hasIdempotencyKey(idempotencyKey)) {
            return checkIdempotency(context, idempotencyKey)
                .doOnNext(r -> metricsRecorder.recordIdempotencyHit(code))
                .switchIfEmpty(Mono.defer(
                    () -> executeWithResilience(context, callerService, idempotencyKey, request)));
          }
          return executeWithResilience(context, callerService, idempotencyKey, request);
        });
  }

  public void evictContext(String code) {
    Optional.ofNullable(contextCache.get(code))
        .filter(ctx -> ctx.getHostConfig() != null)
        .ifPresent(ctx -> contextBuilder.evictWebClient(ctx.getHostConfig().getBaseUrl()));
    contextCache.remove(code);
    contextLoadCache.remove(code);
  }

  private boolean hasIdempotencyKey(String key) {
    return key != null && !key.isBlank();
  }

  private Mono<GatewayResponse> checkIdempotency(OutboundSystemContext context,
      String idempotencyKey) {
    LocalDateTime cutoff = LocalDateTime.now()
        .minusHours(context.getSystem().getIdempotencyTtlHours());
    return historyRepository.findByOutboundSystemIdAndIdempotencyKeyAndCreatedDateAfter(
            context.getSystem().getId(), idempotencyKey, cutoff)
        .map(history -> GatewayResponse.fromCache(
            history.getResponseStatus(),
            Map.of(),
            history.getResponsePayload(),
            Optional.ofNullable(history.getConvertedStatus()).orElse(history.getResponseStatus()),
            Optional.ofNullable(history.getConvertedResponse()).orElse(history.getResponsePayload())
        ));
  }

  private Mono<OutboundSystemContext> loadContext(String code) {
    OutboundSystemContext cached = contextCache.get(code);
    if (cached != null) {
      return Mono.just(cached);
    }
    return contextLoadCache.computeIfAbsent(code, k ->
        loadFromDb(k)
            .cache()
            .doFinally(signal -> contextLoadCache.remove(k))
    );
  }

  private Mono<OutboundSystemContext> loadFromDb(String code) {
    return systemRepository.findByCodeAndActiveTrue(code)
        .flatMap(system -> {
          long systemId = system.getId();
          return Mono.zip(
                  // No resilience row → bypass() synthetic sentinel; all operators skipped.
                  // A row with all flags=false → readTimeOutMs only, no CB/BH/RL/Retry.
                  resilienceRepository.findByOutboundSystemId(systemId)
                      .defaultIfEmpty(OutboundResilienceConfiguration.bypass()),
                  headerRepository.findByOutboundSystemId(systemId).collectList(),
                  authRepository.findByOutboundSystemId(systemId),
                  mapperRepository.findByOutboundSystemIdAndActiveTrue(systemId)
                      .defaultIfEmpty(passthroughMapper()),
                  hostConfigRepository.findByBaseUrl(system.getBaseUrl())
                      .defaultIfEmpty(defaultHostConfig(system.getBaseUrl()))
              )
              .map(t -> contextBuilder.buildContext(
                  system,
                  t.getT5(),
                  t.getT1(),
                  t.getT2(),
                  t.getT3(),
                  t.getT4()
                  )
              );
        })
        .doOnNext(context -> contextCache.put(code, context))
        .switchIfEmpty(Mono.error(new OutboundSystemNotFoundException(code)));
  }

  private Mono<GatewayResponse> executeWithResilience(
      OutboundSystemContext context, String callerService, String idempotencyKey,
      GatewayRequest request) {
    long startTime = System.currentTimeMillis();
    Observation obs = metricsRecorder.startCall(code(context));
    Mono<GatewayResponse> execution = buildExecution(context, request);

    if (!context.getResilience().isBypassed()) {
      // Operator order matters — transformDeferred builds the chain inside-out.
      // Applied left-to-right here means right is outermost at execution time:
      //   RateLimiter (innermost) → Bulkhead → CircuitBreaker → Retry (outermost)
      // Retry must be outermost so each retry attempt passes through CB + Bulkhead.
      // Swapping Retry inside CB means retries bypass the circuit breaker — defeating CB.
      // Only apply operators for mechanisms that were explicitly enabled by the admin.
      if (context.getRateLimiter() != null)
        execution = execution.transformDeferred(RateLimiterOperator.of(context.getRateLimiter()));
      if (context.getBulkhead() != null)
        execution = execution.transformDeferred(BulkheadOperator.of(context.getBulkhead()));
      if (context.getCircuitBreaker() != null)
        execution = execution.transformDeferred(CircuitBreakerOperator.of(context.getCircuitBreaker()));
      if (context.getRetry() != null)
        execution = execution.transformDeferred(RetryOperator.of(context.getRetry()));
    }

    // onErrorResume converts ALL resilience/infra exceptions → GatewayResponse.internalReject(),
    // so flatMap fires for every execution path and history is always saved.
    return execution
        .onErrorResume(e -> Mono.just(toInternalRejectResponse(context, e)))
        .flatMap(response -> {
          long durationMs = System.currentTimeMillis() - startTime;
          metricsRecorder.finalizeCall(obs, response.status(), callerService);
          return saveHistory(context, callerService, idempotencyKey, request, response, durationMs)
              .onErrorResume(err -> {
                log.error(AppLogMessage.message("#GATEWAY - failed to save history for {}: {}", code(context), err.getMessage()).error(err));
                return Mono.empty();
              })
              .thenReturn(response);
        });
  }

  private Mono<GatewayResponse> buildExecution(OutboundSystemContext context,
      GatewayRequest request) {
    return authenticateRequest(context, request)
        .flatMap(credential -> executeHttp(context, request, credential))
        .map(response -> applyMapping(context, response));
  }

  private Mono<AuthCredential> authenticateRequest(OutboundSystemContext context,
      GatewayRequest request) {
    return authStrategyFactory
        .getStrategy(context.getAuth().getAuthType())
        .authenticate(context.getAuth(), encryptionHelper, request.bodyAsString(jsonHelper));
  }

  private Mono<GatewayResponse> executeHttp(
      OutboundSystemContext context, GatewayRequest request, AuthCredential credential) {
    OutboundSystem system = context.getSystem();
    HttpMethod httpMethod = system.getHttpMethodEnum();

    WebClient.RequestBodySpec requestSpec = context.getWebClient()
        .method(httpMethod)
        .uri(uriBuilder -> {
          uriBuilder.path(system.getApiPath());
          if (request.hasQueryParams()) {
            request.queryParams().forEach(uriBuilder::queryParam);
          }
          if (!credential.isEmpty() && credential.location() == TokenInjectLocation.QUERY_PARAM) {
            uriBuilder.queryParam(credential.keyName(), credential.value());
          }
          return uriBuilder.build();
        })
        .headers(headers -> {
          applyConfiguredHeaders(context, headers);
          applyAuthHeader(credential, headers);
        });

    Mono<GatewayResponse> httpMono = (request.hasBody()
        ? requestSpec.bodyValue(request.body())
        : requestSpec
    ).exchangeToMono(response ->
        response.bodyToMono(String.class)
            .defaultIfEmpty("")
            .map(body -> GatewayResponse.passThrough(
                response.statusCode().value(),
                response.headers().asHttpHeaders().entrySet().stream()
                    .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> { List<String> m = new ArrayList<>(a); m.addAll(b); return m; })),
                body
            ))
    );
    // Skip timeout when resilience is bypassed (readTimeOutMs == 0).
    int readTimeoutMs = context.getResilience().getReadTimeOutMs();
    return readTimeoutMs > 0 ? httpMono.timeout(Duration.ofMillis(readTimeoutMs)) : httpMono;
  }

  private void applyConfiguredHeaders(OutboundSystemContext context, HttpHeaders headers) {
    Optional.ofNullable(context.getHeaders())
        .filter(list -> !list.isEmpty())
        .ifPresent(list -> list.stream()
            .map(config -> config.getHeaders(jsonHelper))
            .filter(h -> h != null && !h.isEmpty())
            .forEach(h -> h.forEach(headers::addAll)));
  }

  private void applyAuthHeader(AuthCredential credential, HttpHeaders headers) {
    if (credential.isEmpty()) {
      return;
    }
    if (credential.location() == TokenInjectLocation.HEADER) {
      headers.add(credential.keyName(), credential.value());
    } else if (credential.location() == TokenInjectLocation.COOKIE) {
      headers.add("Cookie", credential.keyName() + "=" + credential.value());
    }
  }

  private GatewayResponse applyMapping(OutboundSystemContext context, GatewayResponse response) {
    return Optional.ofNullable(context.getResponseMapper())
        .filter(m -> !m.isPassThrough())
        .map(m -> GatewayResponse.mapped(
            response.status(), response.headers(), response.body(),
            m.getMappedStatus(response.status()),
            jsltResponseMapper.map(response.body(), m.getMappingScript())))
        .orElse(response);
  }

  private Mono<Void> saveHistory(
      OutboundSystemContext context, String callerService, String idempotencyKey,
      GatewayRequest request, GatewayResponse response, long durationMs) {
    OutboundSystem system = context.getSystem();
    OutboundHistory history = OutboundHistory.builder()
        .outboundSystemId(system.getId())
        .idempotencyKey(idempotencyKey)
        .callerService(callerService)
        .requestMethod(system.getHttpMethod())
        .requestUrl(system.getBaseUrl() + system.getApiPath())
        .requestPayload(request.bodyAsString(jsonHelper))
        .responseStatus(response.status())
        .responsePayload(response.body())
        .durationMs((int) Math.min(durationMs, Integer.MAX_VALUE))
        .convertedStatus(response.convertedStatus())
        .convertedResponse(response.convertedResponse())
        .build();
    if (history.hasIdempotencyKey()) {
      return historyRepository.upsertHistory(
          history.getOutboundSystemId(), history.getIdempotencyKey(),
          history.getCallerService(), history.getRequestMethod(), history.getRequestUrl(),
          history.getRequestPayload(), history.getResponseStatus(), history.getResponsePayload(),
          history.getDurationMs(), history.getConvertedStatus(), history.getConvertedResponse()
      ).then();
    }
    return historyRepository.save(history).then();
  }

  private OutboundResponseMapperConfiguration passthroughMapper() {
    return OutboundResponseMapperConfiguration.builder()
        .converterType(ConverterType.NONE)
        .build();
  }

  private OutboundHostConfiguration defaultHostConfig(String baseUrl) {
    return OutboundHostConfiguration.productionDefault(baseUrl);
  }

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
   * <p>Always returns a value (never re-throws) so that {@code doOnSuccess} in
   * {@link #executeWithResilience} fires and history is saved for every call.
   */
  private GatewayResponse toInternalRejectResponse(OutboundSystemContext context, Throwable e) {
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

    log.error(AppLogMessage.message("#GATEWAY - unexpected error for {}: {}", code(context), e.getMessage()).error(e));
    return GatewayResponse.internalReject(503, "internal gateway error");
  }

  private String code(OutboundSystemContext context) {
    return context.getSystem().getCode();
  }
}
