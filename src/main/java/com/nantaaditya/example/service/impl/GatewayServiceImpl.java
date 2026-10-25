package com.nantaaditya.example.service.impl;

import com.google.gson.Gson;
import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.factory.AuthStrategyFactory;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.helper.GatewayHelper;
import com.nantaaditya.example.gateway.SystemContextLoader;
import com.nantaaditya.example.idempotency.IdempotencyGuard;
import com.nantaaditya.example.helper.GsonHelper;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.helper.OutboundMetricsRecorder;
import com.nantaaditya.example.helper.ParameterInjector;
import com.nantaaditya.example.helper.ParameterResolver;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.ContextDTO;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.dto.ResolvedParameters;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.response.GatewayResponse;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.service.GatewayService;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.micrometer.observation.Observation;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
 *   <li>{@link SystemContextLoader#load} — resolves {@link OutboundSystemContext} from the two-layer
 *       cache (hot cache → shared database load).</li>
 *   <li>{@link IdempotencyGuard} — if {@code x-idempotency-key} is present: replays a stored result
 *       within the TTL window, merges overlapping calls with the same key (also across instances)
 *       and runs the call only for the one that owns the key.</li>
 *   <li>{@link #executeWithResilience} — runs authentication, HTTP call, and the resilience pipeline.</li>
 *   <li>{@link com.nantaaditya.example.helper.GatewayHelper#applyMapping} — JSLT transform or pass-through.</li>
 *   <li>{@link #saveHistory} — saves the execution history for auditing and idempotency support.
 *       It is awaited before the response is returned; a failure is logged and swallowed.</li>
 * </ol>
 *
 * <h3>Resilience operator order</h3>
 * <p>{@code transformDeferred} builds the chain inside-out. The order below results in Retry
 * being the outermost wrapper at execution time:
 * <pre>  RateLimiter → Bulkhead → CircuitBreaker → Retry (outermost)</pre>
 * Retry must be outermost so each retry re-evaluates CB and Bulkhead. Placing Retry inside
 * CB would bypass the circuit breaker on retries — defeating its purpose.
 *
 * <h3>History save</h3>
 * <p>The insert is awaited, so it adds to the call's latency, but {@link #saveHistory} failures
 * are logged and swallowed: a history write failure must never surface as a gateway call
 * failure to the caller.
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class GatewayServiceImpl implements GatewayService {

  private final SystemContextLoader contextLoader;
  private final OutboundHistoryRepository historyRepository;
  private final AuthStrategyFactory authStrategyFactory;
  private final EncryptionHelper encryptionHelper;
  private final JsonHelper jsonHelper;
  private final OutboundMetricsRecorder metricsRecorder;
  private final Gson gson;
  private final GatewayHelper gatewayHelper;
  private final ParameterResolver parameterResolver;
  private final ParameterInjector parameterInjector;
  private final IdempotencyGuard idempotencyGuard;

  @Override
  public Mono<GatewayResponse> execute(String code, String callerService, String idempotencyKey,
      GatewayRequest request) {
    return contextLoader.load(code)
        .flatMap(context -> {
          if (hasIdempotencyKey(idempotencyKey)) {
            OutboundSystem system = context.getSystem();
            // replay of a stored result, merging of overlapping calls (here and across instances)
            return idempotencyGuard.guard(system.getId(), idempotencyKey, system.getIdempotencyTtlHours(),
                    () -> executeResolved(context, callerService, idempotencyKey, request))
                .doOnNext(r -> {
                  if (r.fromCache()) {
                    metricsRecorder.recordIdempotencyHit(code);
                  }
                });
          }
          return executeResolved(context, callerService, idempotencyKey, request);
        });
  }

  // Parameters are resolved before the resilience pipeline: an unknown/invalid injectKey is a
  // client error (400) and must not be swallowed into an internal-reject response.
  private Mono<GatewayResponse> executeResolved(OutboundSystemContext context, String callerService,
      String idempotencyKey, GatewayRequest request) {
    return parameterResolver.resolve(context, request)
        .flatMap(parameters -> executeWithResilience(context, callerService, idempotencyKey, request, parameters));
  }

  @Override
  public void evictContext(String code) {
    contextLoader.evictContext(code);
  }

  @Override
  public void evictHost(String baseUrl) {
    contextLoader.evictHost(baseUrl);
  }

  private boolean hasIdempotencyKey(String key) {
    return key != null && !key.isBlank();
  }

  private Mono<GatewayResponse> executeWithResilience(
      OutboundSystemContext context, String callerService, String idempotencyKey,
      GatewayRequest request, ResolvedParameters parameters) {
    long startTime = System.currentTimeMillis();
    Observation observation = metricsRecorder.startCall(gatewayHelper.code(context), hostName(context));
    // One counter per invocation — RetryOperator re-subscribes to buildExecution on each
    // retry attempt, so doOnSubscribe fires once per attempt. retryCount = attempts - 1.
    AtomicInteger attempts = new AtomicInteger(0);
    Mono<GatewayResponse> execution = buildExecution(context, request, parameters, attempts);
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

  private String hostName(OutboundSystemContext context) {
    return Optional.ofNullable(context.getHostConfig())
        .map(OutboundHostConfiguration::getBaseUrl)
        .orElse("unknown");
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
      GatewayRequest request, ResolvedParameters parameters, AtomicInteger attempts) {
    // Signed once here and reused verbatim for the outbound call below — HMAC/RSA must sign
    // exactly the bytes that go on the wire, not a separately re-serialized copy of the body.
    // BODY parameters are injected before signing so the signature covers them; the original
    // request (without injected values) is what saveHistory persists.
    Object outboundBody = parameterInjector.injectBody(request.body(), parameters.bodyItems());
    String cleanedBody = GsonHelper.cleanJson(jsonHelper.toJsonString(outboundBody), gson);
    return authenticateRequest(context, cleanedBody)
        .doOnSubscribe(__ -> attempts.incrementAndGet())
        .flatMap(credential -> executeHttp(context, request, parameters, cleanedBody, credential))
        .map(response -> gatewayHelper.applyMapping(context, response));
  }

  private Mono<AuthCredential> authenticateRequest(OutboundSystemContext context, String cleanedBody) {
    return authStrategyFactory
        .getStrategy(context.getAuth().getAuthType())
        .authenticate(context.getAuth(), encryptionHelper, cleanedBody);
  }

  private Mono<GatewayResponse> executeHttp(OutboundSystemContext context, GatewayRequest request,
      ResolvedParameters parameters, String cleanedBody, AuthCredential credential) {
    OutboundSystem system = context.getSystem();
    HttpMethod httpMethod = system.getHttpMethodEnum();

    WebClient.RequestBodySpec requestSpec = context.getWebClient()
        .method(httpMethod)
        .uri(uriBuilder -> {
          uriBuilder.path(system.getApiPath());
          if (request.hasQueryParams()) {
            request.queryParams().forEach(uriBuilder::queryParam);
          }
          // Injected values go through URI template variables so they are encoded as ONE value:
          // a literal "{x}" is not expanded and "&" / "+" cannot smuggle extra query params.
          Map<String, Object> uriVariables = new HashMap<>();
          parameters.queryParams().forEach((name, value) -> {
            String variable = "injected" + uriVariables.size();
            uriVariables.put(variable, value);
            uriBuilder.queryParam(name, "{" + variable + "}");
          });
          if (!credential.isEmpty() && credential.location() == TokenInjectLocation.QUERY_PARAM) {
            uriBuilder.queryParam(credential.keyName(), credential.value());
          }
          return uriBuilder.build(uriVariables);
        })
        .headers(headers -> {
          gatewayHelper.applyConfiguredHeaders(context, headers);
          parameters.headers().forEach(headers::set);
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
