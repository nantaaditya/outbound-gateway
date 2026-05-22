package com.nantaaditya.example.helper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboundMetricsRecorder {

  private final ObservationRegistry observationRegistry;
  private final MeterRegistry meterRegistry;

  private final ConcurrentHashMap<String, Counter> idempotencyCounters = new ConcurrentHashMap<>();

  public Observation startCall(String code) {
    return Observation.createNotStarted("api.public", observationRegistry)
        .lowCardinalityKeyValue("feature", code)
        .start();
  }

  public void finalizeCall(Observation observation, int httpStatus, String callerService) {
    observation
        .lowCardinalityKeyValue("responseCode", String.valueOf(httpStatus))
        .lowCardinalityKeyValue("callerService", callerService != null ? callerService : "unknown")
        .stop();
  }

  public void recordIdempotencyHit(String code) {
    idempotencyCounters.computeIfAbsent(code, c ->
        Counter.builder("api.public.idempotency")
            .tag("feature", c)
            .register(meterRegistry))
        .increment();
  }
}
