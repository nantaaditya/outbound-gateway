package com.nantaaditya.example.helper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Records Micrometer metrics for every outbound gateway call.
 *
 * <h3>Observation vs pre-cached Counter — why two instruments</h3>
 * <p>{@link #startCall} creates a new {@link Observation} per call. Observations carry per-call
 * state (latency, status tags) so they cannot be shared or pre-cached.
 *
 * <p>{@link #recordIdempotencyHit} uses a {@link Counter} cached in {@code idempotencyCounters}.
 * Counter creation (registry lookup + tag binding) is not free — doing it on every call under
 * load would add contention on the {@code MeterRegistry}. {@link ConcurrentHashMap#computeIfAbsent}
 * ensures the counter is registered exactly once per system code and reused on every subsequent
 * idempotency cache hit.
 *
 * <h3>Low-cardinality tags only</h3>
 * <p>All tags added here ({@code feature}, {@code responseCode}, {@code callerService}) have
 * bounded cardinality. High-cardinality values (request IDs, idempotency keys, body content)
 * must never be added as tags — each unique value creates a new time-series in Prometheus,
 * leading to cardinality explosion and OOM in the metrics backend.
 */
@Component
@RequiredArgsConstructor
public class OutboundMetricsRecorder {

  private final ObservationRegistry observationRegistry;
  private final MeterRegistry meterRegistry;

  private static final int MAX_CALLER_TAG_LENGTH = 64;

  private final ConcurrentHashMap<String, Counter> idempotencyCounters = new ConcurrentHashMap<>();

  public Observation startCall(String code) {
    return Observation.createNotStarted("api.public", observationRegistry)
        .lowCardinalityKeyValue("feature", code)
        .start();
  }

  public void finalizeCall(Observation observation, int httpStatus, String callerService) {
    observation
        .lowCardinalityKeyValue("responseCode", String.valueOf(httpStatus))
        .lowCardinalityKeyValue("callerService", normalizeCallerService(callerService))
        .stop();
  }

  private String normalizeCallerService(String raw) {
    if (raw == null || raw.isBlank()) return "unknown";
    String normalized = raw.trim().replaceAll("[^a-zA-Z0-9_\\-]", "_");
    return normalized.length() > MAX_CALLER_TAG_LENGTH
        ? normalized.substring(0, MAX_CALLER_TAG_LENGTH)
        : normalized;
  }

  public void recordIdempotencyHit(String code) {
    idempotencyCounters.computeIfAbsent(code, c ->
        Counter.builder("api.public.idempotency")
            .tag("feature", c)
            .register(meterRegistry))
        .increment();
  }
}
