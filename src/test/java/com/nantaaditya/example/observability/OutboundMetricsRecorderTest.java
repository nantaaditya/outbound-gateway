package com.nantaaditya.example.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
    ObservationRegistry observationRegistry = ObservationRegistry.create();
    observationRegistry.observationConfig()
        .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
    recorder = new OutboundMetricsRecorder(observationRegistry, meterRegistry);
  }

  @Test
  @DisplayName("startCall + finalizeCall registers api.public timer with feature, host, responseCode, callerService tags")
  void recordCall_incrementsCallCounter() {
    Observation obs = recorder.startCall("xendit", "http://localhost:8080");
    recorder.finalizeCall(obs, 200, "payment-svc");

    Timer timer = meterRegistry.find("api.public")
        .tags("feature", "xendit", "host", "http://localhost:8080",
            "responseCode", "200", "callerService", "payment-svc")
        .timer();

    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1L);
  }

  @Test
  @DisplayName("startCall with null host uses 'unknown' host tag")
  void recordCall_nullHost_usesUnknown() {
    Observation obs = recorder.startCall("xendit", null);
    recorder.finalizeCall(obs, 200, "svc");

    Timer timer = meterRegistry.find("api.public")
        .tags("feature", "xendit", "host", "unknown")
        .timer();

    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1L);
  }

  @Test
  @DisplayName("startCall + finalizeCall records elapsed duration in api.public timer")
  void recordCall_recordsDuration() {
    Observation obs = recorder.startCall("xendit", "http://localhost:8080");
    recorder.finalizeCall(obs, 200, "payment-svc");

    Timer timer = meterRegistry.find("api.public")
        .tags("feature", "xendit")
        .timer();

    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1L);
    assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(0.0);
  }

  @Test
  @DisplayName("finalizeCall with null callerService uses 'unknown' tag")
  void recordCall_nullCallerService_usesUnknown() {
    Observation obs = recorder.startCall("xendit", "http://localhost:8080");
    recorder.finalizeCall(obs, 200, null);

    Timer timer = meterRegistry.find("api.public")
        .tags("feature", "xendit", "callerService", "unknown")
        .timer();

    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1L);
  }

  @Test
  @DisplayName("multiple calls accumulate count per responseCode in api.public timer")
  void recordCall_multipleInvocations_accumulatesCount() {
    Observation obs1 = recorder.startCall("midtrans", "http://localhost:8080");
    recorder.finalizeCall(obs1, 200, "order-svc");
    Observation obs2 = recorder.startCall("midtrans", "http://localhost:8080");
    recorder.finalizeCall(obs2, 200, "order-svc");
    Observation obs3 = recorder.startCall("midtrans", "http://localhost:8080");
    recorder.finalizeCall(obs3, 500, "order-svc");

    Timer okTimer = meterRegistry.find("api.public")
        .tags("feature", "midtrans", "responseCode", "200")
        .timer();
    Timer errTimer = meterRegistry.find("api.public")
        .tags("feature", "midtrans", "responseCode", "500")
        .timer();

    assertThat(okTimer).isNotNull();
    assertThat(okTimer.count()).isEqualTo(2L);
    assertThat(errTimer).isNotNull();
    assertThat(errTimer.count()).isEqualTo(1L);
  }

  @Test
  @DisplayName("recordIdempotencyHit registers api.public.idempotency counter with feature tag")
  void recordIdempotencyHit_incrementsCounter() {
    recorder.recordIdempotencyHit("xendit");

    Counter counter = meterRegistry.find("api.public.idempotency")
        .tags("feature", "xendit")
        .counter();

    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("recordIdempotencyHit accumulates across multiple hits")
  void recordIdempotencyHit_multipleHits_accumulatesCount() {
    recorder.recordIdempotencyHit("xendit");
    recorder.recordIdempotencyHit("xendit");

    Counter counter = meterRegistry.find("api.public.idempotency")
        .tags("feature", "xendit")
        .counter();

    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(2.0);
  }

  @Test
  @DisplayName("different codes produce independent api.public timers")
  void recordCall_differentCodes_independentCounters() {
    Observation obs1 = recorder.startCall("xendit", "http://localhost:8080");
    recorder.finalizeCall(obs1, 200, "svc");
    Observation obs2 = recorder.startCall("midtrans", "http://localhost:8080");
    recorder.finalizeCall(obs2, 200, "svc");

    Timer xendit = meterRegistry.find("api.public").tags("feature", "xendit").timer();
    Timer midtrans = meterRegistry.find("api.public").tags("feature", "midtrans").timer();

    assertThat(xendit.count()).isEqualTo(1L);
    assertThat(midtrans.count()).isEqualTo(1L);
  }
}
