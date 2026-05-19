package com.nantaaditya.example.helper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboundMetricsRecorder {

  private final MeterRegistry meterRegistry;

  public void recordCall(String code, int httpStatus, String callerService, long durationMs) {
    Counter.builder("outbound.calls")
        .tag("code", code)
        .tag("http_status", String.valueOf(httpStatus))
        .tag("caller_service", callerService != null ? callerService : "unknown")
        .register(meterRegistry)
        .increment();

    Timer.builder("outbound.calls.duration")
        .tag("code", code)
        .register(meterRegistry)
        .record(Duration.ofMillis(durationMs));
  }

  public void recordIdempotencyHit(String code) {
    Counter.builder("outbound.idempotency.hits")
        .tag("code", code)
        .register(meterRegistry)
        .increment();
  }
}
