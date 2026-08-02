package com.nantaaditya.example.service.impl;

import com.google.gson.Gson;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.factory.AuthStrategyFactory;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.helper.GatewayHelper;
import com.nantaaditya.example.helper.GsonHelper;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.helper.OutboundMetricsRecorder;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.ContextDTO;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.exception.HostConfigNotFoundException;
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
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.micrometer.observation.Observation;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
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
 *   <li>{@link #executeWithResilience} — runs authentication, HTTP call, and the resilience pipeline.</li>
 *   <li>{@link com.nantaaditya.example.helper.GatewayHelper#applyMapping} — JSLT transform or pass-through.</li>
 *   <li>{@link #saveHistory} — fire-and-forget; saves the execution history for auditing
 *       and idempotency support.</li>
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
  private final JsonHelper jsonHelper;
  private final OutboundMetricsRecorder metricsRecorder;
  private final Gson gson;
  private final GatewayHelper gatewayHelper;

  private final ConcurrentMap<String, OutboundSystemContext> contextCache = new ConcurrentHashMap<>();

  // Thundering-herd protection: computeIfAbsent + Mono.cache() ensures only ONE DB load
  // fires per code even when many requests arrive concurrently before the first load completes.
  // doFinally removes the entry so the next miss after eviction triggers a fresh load.
  private final ConcurrentMap<String, Mono<OutboundSystemContext>> contextLoadCache = new ConcurrentHashMap<>();

  @Override
  public Mono<GatewayResponse> execute(String code, String callerService, String idempotencyKey,
      GatewayRequest request) {
    return loadContext(code)
        .flatMap(context -> {
          if (hasIdempotencyKey(idempotencyKey)) {
            return checkIdempotency(context, idempotencyKey)
                .doOnNext(r -> metricsRecorder.recordIdempotencyHit(code))
                .switchIfEmpty(Mono.defer(() -> executeWithResilience(context, callerService, idempotencyKey, request)));
          }
          return executeWithResilience(context, callerService, idempotencyKey, request);
        });
  }

  public void evictContext(String code) {
    OutboundSystemContext evicted = contextCache.remove(code);
    if (evicted != null && evicted.getHostConfig() != null) {
      contextBuilder.evictWebClient(evicted.getHostConfig().getBaseUrl());
    }
    contextLoadCache.remove(code);
  }

  private boolean hasIdempotencyKey(String key) {
    return key != null && !key.isBlank();
  }

  private Mono<GatewayResponse> checkIdempotency(OutboundSystemContext context,
      String idempotencyKey) {
    LocalDateTime cutoff = LocalDateTime.now()
        .minusHours(context.getSystem().getIdempotencyTtlHours());
    return historyRepository.findFirstByOutboundSystemIdAndIdempotencyKeyAndCreatedDateAfterOrderByCreatedDateDesc(
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
                  // No authentication row → treat as NONE so execution proceeds without authentication headers
                  authRepository.findByOutboundSystemId(systemId)
                      .defaultIfEmpty(OutboundAuthConfiguration.empty(systemId)),
                  mapperRepository.findByOutboundSystemIdAndActiveTrue(systemId)
                      .defaultIfEmpty(OutboundResponseMapperConfiguration.empty(systemId)),
                  hostConfigRepository.findById(system.getHostConfigurationId())
                      .switchIfEmpty(Mono.error(new HostConfigNotFoundException(system.getHostConfigurationId())))
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
    Observation observation = metricsRecorder.startCall(gatewayHelper.code(context));
    // One counter per invocation — RetryOperator re-subscribes to buildExecution on each
    // retry attempt, so doOnSubscribe fires once per attempt. retryCount = attempts - 1.
    AtomicInteger attempts = new AtomicInteger(0);
    Mono<GatewayResponse> execution = buildExecution(context, request, attempts);
    execution = decorateResilience(context, execution);

    // onErrorResume converts ALL resilience/infra exceptions → GatewayResponse.internalReject(),
    // so flatMap fires for every execution path and history is always saved.
    return execution
        .onErrorResume(e -> Mono.just(gatewayHelper.toInternalRejectResponse(context, e)))
        .flatMap(response -> {
          long durationMs = System.currentTimeMillis() - startTime;
          int retryCount = Math.max(0, attempts.get() - 1);
          metricsRecorder.finalizeCall(observation, response.status(), callerService);

          return saveHistory(context, callerService, idempotencyKey, request, response, durationMs, retryCount)
              .onErrorResume(err -> {
                log.error(AppLogMessage.message("#GATEWAY - failed to save history for {}: {}",
                    gatewayHelper.code(context), err.getMessage()).error(err));
                return Mono.empty();
              })
              .thenReturn(response);
        });
  }

  private Mono<GatewayResponse> decorateResilience(OutboundSystemContext context,
      Mono<GatewayResponse> execution) {
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
    return execution;
  }

  private Mono<GatewayResponse> buildExecution(OutboundSystemContext context,
      GatewayRequest request, AtomicInteger attempts) {
    // Signed once here and reused verbatim for the outbound call below — HMAC/RSA must sign
    // exactly the bytes that go on the wire, not a separately re-serialized copy of the body.
    String cleanedBody = GsonHelper.cleanJson(request.bodyAsString(jsonHelper), gson);
    return authenticateRequest(context, cleanedBody)
        .doOnSubscribe(__ -> attempts.incrementAndGet())
        .flatMap(credential -> executeHttp(context, request, cleanedBody, credential))
        .map(response -> gatewayHelper.applyMapping(context, response));
  }

  private Mono<AuthCredential> authenticateRequest(OutboundSystemContext context, String cleanedBody) {
    return authStrategyFactory
        .getStrategy(context.getAuth().getAuthType())
        .authenticate(context.getAuth(), encryptionHelper, cleanedBody);
  }

  private Mono<GatewayResponse> executeHttp(OutboundSystemContext context, GatewayRequest request,
      String cleanedBody, AuthCredential credential) {
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
          gatewayHelper.applyConfiguredHeaders(context, headers);
          gatewayHelper.applyAuthHeader(credential, headers);
        });

    // Send the same cleanedBody string that was signed above — not request.body() re-serialized
    // independently by WebFlux's encoder, which would silently break HMAC/RSA verification.
    Mono<GatewayResponse> httpMono = (request.hasBody()
        ? requestSpec.bodyValue(cleanedBody)
        : requestSpec
    ).exchangeToMono(response ->
        response.bodyToMono(String.class)
            .defaultIfEmpty("")
            .map(body -> GatewayResponse.passThrough(
                response.statusCode().value(),
                gatewayHelper.collectResponseHeaders(response),
                body
            ))
    );
    // Skip timeout when resilience is bypassed (readTimeOutMs == 0).
    int readTimeoutMs = context.getResilience().getReadTimeOutMs();
    return readTimeoutMs > 0 ? httpMono.timeout(Duration.ofMillis(readTimeoutMs)) : httpMono;
  }

  private Mono<Void> saveHistory(
      OutboundSystemContext context, String callerService, String idempotencyKey,
      GatewayRequest request, GatewayResponse response, long durationMs, int retryCount) {
    return Mono.deferContextual(ctx -> {
      String requestId = ctx.<ContextDTO>getOrEmpty("context")
          .map(ContextDTO::getRequestId)
          .orElse(null);
      String requestPayload = request.bodyAsString(jsonHelper);
      Map<String, Object> requestHeaders = gatewayHelper.resolveRequestHeaders(context);
      OutboundHistory history = OutboundHistory.create(context.getSystem(),
          context.getHostConfig().getBaseUrl(), callerService,
          idempotencyKey, requestId, requestPayload,
          requestHeaders, response, durationMs, retryCount
      );

      return historyRepository.saveHistory(history).then();
    });
  }

}
