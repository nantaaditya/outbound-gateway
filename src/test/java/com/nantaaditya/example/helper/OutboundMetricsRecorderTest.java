package com.nantaaditya.example.helper;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboundMetricsRecorderTest {

  private SimpleMeterRegistry meterRegistry;
  private OutboundMetricsRecorder recorder;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    recorder = new OutboundMetricsRecorder(meterRegistry);
  }

  @Test
  @DisplayName("recordCall increments outbound.calls counter with correct tags")
  void recordCall_incrementsCallCounter() {
    recorder.recordCall("xendit", 200, "payment-svc", 120L);

    Counter counter = meterRegistry.find("outbound.calls")
        .tags("code", "xendit", "http_status", "200", "caller_service", "payment-svc")
        .counter();

    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("recordCall records duration in outbound.calls.duration timer")
  void recordCall_recordsDuration() {
    recorder.recordCall("xendit", 200, "payment-svc", 120L);

    Timer timer = meterRegistry.find("outbound.calls.duration")
        .tags("code", "xendit")
        .timer();

    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);
    assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(120.0);
  }

  @Test
  @DisplayName("recordCall with null callerService uses 'unknown' tag")
  void recordCall_nullCallerService_usesUnknown() {
    recorder.recordCall("xendit", 200, null, 50L);

    Counter counter = meterRegistry.find("outbound.calls")
        .tags("code", "xendit", "caller_service", "unknown")
        .counter();

    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("recordCall accumulates count across multiple calls")
  void recordCall_multipleInvocations_accumulatesCount() {
    recorder.recordCall("midtrans", 200, "order-svc", 80L);
    recorder.recordCall("midtrans", 200, "order-svc", 90L);
    recorder.recordCall("midtrans", 500, "order-svc", 200L);

    Counter okCounter = meterRegistry.find("outbound.calls")
        .tags("code", "midtrans", "http_status", "200")
        .counter();
    Counter errCounter = meterRegistry.find("outbound.calls")
        .tags("code", "midtrans", "http_status", "500")
        .counter();

    assertThat(okCounter).isNotNull();
    assertThat(okCounter.count()).isEqualTo(2.0);
    assertThat(errCounter).isNotNull();
    assertThat(errCounter.count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("recordIdempotencyHit increments outbound.idempotency.hits counter")
  void recordIdempotencyHit_incrementsCounter() {
    recorder.recordIdempotencyHit("xendit");

    Counter counter = meterRegistry.find("outbound.idempotency.hits")
        .tags("code", "xendit")
        .counter();

    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("recordIdempotencyHit accumulates across multiple hits")
  void recordIdempotencyHit_multipleHits_accumulatesCount() {
    recorder.recordIdempotencyHit("xendit");
    recorder.recordIdempotencyHit("xendit");

    Counter counter = meterRegistry.find("outbound.idempotency.hits")
        .tags("code", "xendit")
        .counter();

    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(2.0);
  }

  @Test
  @DisplayName("different codes produce independent counters")
  void recordCall_differentCodes_independentCounters() {
    recorder.recordCall("xendit", 200, "svc", 50L);
    recorder.recordCall("midtrans", 200, "svc", 50L);

    Counter xendit = meterRegistry.find("outbound.calls").tags("code", "xendit").counter();
    Counter midtrans = meterRegistry.find("outbound.calls").tags("code", "midtrans").counter();

    assertThat(xendit.count()).isEqualTo(1.0);
    assertThat(midtrans.count()).isEqualTo(1.0);
  }
}
