package com.nantaaditya.example.helper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ReactorEventBusHelperTest {

  private ReactorEventBusHelper eventBus;

  @BeforeEach
  void setUp() throws Exception {
    eventBus = new ReactorEventBusHelper();
    clearStaticSinks();
  }

  @AfterEach
  void tearDown() throws Exception {
    clearStaticSinks();
  }

  @Test
  @DisplayName("createSinks stores a sink that can be retrieved by getSinks")
  void createSinks_storesSink_retrievableByGetSinks() {
    Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(10);

    eventBus.createSinks("my-event", sink);

    assertThat(eventBus.getSinks("my-event")).isSameAs(sink);
  }

  @Test
  @DisplayName("getSinks returns null for an unknown name")
  void getSinks_unknownName_returnsNull() {
    assertThat(eventBus.getSinks("unknown")).isNull();
  }

  @Test
  @DisplayName("publish emits an event to the registered sink")
  void publish_emitsEventToSink() {
    Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(10);
    eventBus.createSinks("test-channel", sink);

    StepVerifier.create(sink.asFlux().take(1))
        .then(() -> eventBus.publish("test-channel", "hello"))
        .expectNext("hello")
        .verifyComplete();
  }

  @Test
  @DisplayName("publish with null name logs a warning and returns without throwing")
  void publish_nullName_doesNotThrow() {
    eventBus.publish(null, "payload");
  }

  @Test
  @DisplayName("publish with null event logs a warning and returns without throwing")
  void publish_nullEvent_doesNotThrow() {
    Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(10);
    eventBus.createSinks("ch", sink);

    eventBus.publish("ch", null);
  }

  @Test
  @DisplayName("publish when sink is not found logs a warning and returns without throwing")
  void publish_sinkNotFound_doesNotThrow() {
    eventBus.publish("nonexistent-sink", "event");
  }

  @Test
  @DisplayName("consume returns a Flux backed by the registered sink")
  void consume_registeredSink_returnsFlux() {
    Sinks.Many<Integer> sink = Sinks.many().multicast().onBackpressureBuffer(10);
    eventBus.createSinks("int-channel", sink);

    StepVerifier.create(eventBus.<Integer>consume("int-channel", null).take(2))
        .then(() -> {
          sink.tryEmitNext(1);
          sink.tryEmitNext(2);
        })
        .expectNext(1, 2)
        .verifyComplete();
  }

  @Test
  @DisplayName("consume with null scheduler falls back to immediate scheduler")
  void consume_nullScheduler_usesImmediate() {
    Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(10);
    eventBus.createSinks("null-sched", sink);

    var flux = eventBus.<String>consume("null-sched", null);
    assertThat(flux).isNotNull();
  }

  @Test
  @DisplayName("consume for unknown sink returns an empty Flux")
  void consume_unknownSink_returnsEmptyFlux() {
    StepVerifier.create(eventBus.consume("no-such-sink", null))
        .verifyComplete();
  }

  @SuppressWarnings("unchecked")
  private void clearStaticSinks() throws Exception {
    Field f = ReactorEventBusHelper.class.getDeclaredField("SINKS");
    f.setAccessible(true);
    ((Map<?, ?>) f.get(null)).clear();
  }
}
