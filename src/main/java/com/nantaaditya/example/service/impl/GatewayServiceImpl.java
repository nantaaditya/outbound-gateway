package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.gateway.HistoryRecorder;
import com.nantaaditya.example.gateway.OutboundRequestExecutor;
import com.nantaaditya.example.gateway.ResilienceDecorator;
import com.nantaaditya.example.gateway.SystemContextLoader;
import com.nantaaditya.example.helper.GatewayHelper;
import com.nantaaditya.example.helper.OutboundMetricsRecorder;
import com.nantaaditya.example.helper.ParameterResolver;
import com.nantaaditya.example.idempotency.IdempotencyGuard;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.dto.ResolvedParameters;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.response.GatewayResponse;
import com.nantaaditya.example.service.GatewayService;
import io.micrometer.observation.Observation;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Central orchestrator for all outbound gateway calls. It only wires the steps together; each step lives in
 * its own class.
 *
 * <h3>Execution pipeline (per call)</h3>
 * <ol>
 *   <li>{@link SystemContextLoader#load} — resolves {@link OutboundSystemContext} from the two-layer
 *       cache (hot cache → shared database load).</li>
 *   <li>{@link IdempotencyGuard} — if {@code x-idempotency-key} is present: replays a stored result
 *       within the TTL window, merges overlapping calls with the same key (also across instances)
 *       and runs the call only for the one that owns the key.</li>
 *   <li>{@link ParameterResolver} — resolves the {@code injectKeys}; a bad key is a client error (400).</li>
 *   <li>{@link OutboundRequestExecutor} wrapped by {@link ResilienceDecorator} — injects parameters,
 *       authenticates/signs, sends the HTTP call and applies the response mapping. The resilience operator
 *       order is defined in {@link ResilienceDecorator} only.</li>
 *   <li>{@link HistoryRecorder} — saves the execution history for auditing and idempotency support. It is
 *       awaited before the response is returned, so it adds to the call's latency, but a failure is logged
 *       and swallowed: a history write problem never fails the call.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class GatewayServiceImpl implements GatewayService {

  private final SystemContextLoader contextLoader;
  private final OutboundMetricsRecorder metricsRecorder;
  private final GatewayHelper gatewayHelper;
  private final ParameterResolver parameterResolver;
  private final IdempotencyGuard idempotencyGuard;
  private final ResilienceDecorator resilienceDecorator;
  private final OutboundRequestExecutor requestExecutor;
  private final HistoryRecorder historyRecorder;

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
    // One counter per invocation — RetryOperator re-subscribes to the executor's Mono on each
    // retry attempt, so the callback fires once per attempt. retryCount = attempts - 1.
    AtomicInteger attempts = new AtomicInteger(0);
    Mono<GatewayResponse> execution =
        requestExecutor.execute(context, request, parameters, attempts::incrementAndGet);
    execution = resilienceDecorator.decorate(context, execution);

    // onErrorResume converts ALL resilience/infra exceptions → GatewayResponse.internalReject(),
    // so flatMap fires for every execution path and history is always saved.
    return execution
        .onErrorResume(e -> Mono.just(gatewayHelper.toInternalRejectResponse(context, e)))
        .flatMap(response -> {
          long durationMs = System.currentTimeMillis() - startTime;
          int retryCount = Math.max(0, attempts.get() - 1);
          metricsRecorder.finalizeCall(observation, response.status(), callerService);

          return historyRecorder
              .record(context, callerService, idempotencyKey, request, response, durationMs, retryCount)
              .thenReturn(response);
        });
  }

  private String hostName(OutboundSystemContext context) {
    return Optional.ofNullable(context.getHostConfig())
        .map(OutboundHostConfiguration::getBaseUrl)
        .orElse("unknown");
  }
}
