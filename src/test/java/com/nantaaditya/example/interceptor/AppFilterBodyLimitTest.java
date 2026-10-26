package com.nantaaditya.example.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.helper.ContextHelper;
import com.nantaaditya.example.helper.ObservationHelper;
import com.nantaaditya.example.helper.TracerHelper;
import com.nantaaditya.example.logging.EventLogHelper;
import com.nantaaditya.example.model.dto.ContextDTO;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AppFilterBodyLimitTest {

  private static final int LIMIT = 250;
  private static final String PATH = "/api/gateway/execute/pay";

  private final EventLogHelper eventLogHelper = mock(EventLogHelper.class);
  private final WebFilterChain chain = mock(WebFilterChain.class);
  private final AtomicInteger observationsStopped = new AtomicInteger();

  private AppFilter filter;

  @BeforeEach
  void setUp() {
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
      @Override
      public boolean supportsContext(Observation.Context context) {
        return true;
      }

      @Override
      public void onStop(Observation.Context context) {
        observationsStopped.incrementAndGet();
      }
    });
    ObservationHelper observationHelper =
        new ObservationHelper(registry, mock(TracerHelper.class), mock(ContextHelper.class));
    filter = new AppFilter(eventLogHelper, observationHelper);
    ReflectionTestUtils.setField(filter, "contextPath", "");
    ReflectionTestUtils.setField(filter, "maxBodyBytes", LIMIT);
    when(chain.filter(any())).thenReturn(Mono.empty());
  }

  @Test
  @DisplayName("an oversized body is rejected with 413 while it is being read, not after it was buffered in full")
  void filter_oversizedBody_rejectedWhileReading() {
    AtomicInteger chunksRead = new AtomicInteger();
    Flux<? extends DataBuffer> body = Flux.range(0, 100)
        .map(i -> DefaultDataBufferFactory.sharedInstance.wrap(new byte[100]))
        .doOnNext(chunk -> chunksRead.incrementAndGet());
    ServerWebExchange exchange = post(body);

    StepVerifier.create(filter.filter(exchange, chain))
        .expectErrorSatisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE))
        .verify();

    assertThat(chunksRead.get()).isLessThan(10);       // 100 chunks were available; reading stopped at the limit
    verify(chain, never()).filter(any());
  }

  @Test
  @DisplayName("a rejected request still closes its observation and writes its event log, with an empty payload")
  void filter_oversizedBody_closesObservationAndSavesEventLog() {
    ServerWebExchange exchange = post(Flux.just(
        DefaultDataBufferFactory.sharedInstance.wrap(new byte[LIMIT + 1])));

    StepVerifier.create(filter.filter(exchange, chain)).expectError(ResponseStatusException.class).verify();

    assertThat(observationsStopped).hasValue(1);
    verify(eventLogHelper).save(eq(exchange), any(ContextDTO.class));
    assertThat((byte[]) exchange.getAttribute("cachedRequestBody")).isEmpty();   // never the rejected megabytes
  }

  @Test
  @DisplayName("a body exactly at the limit is accepted")
  void filter_bodyAtLimit_isAccepted() {
    ServerWebExchange exchange = post(Flux.just(
        DefaultDataBufferFactory.sharedInstance.wrap(new byte[LIMIT])));

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain).filter(any());
  }

  @Test
  @DisplayName("a body one byte over the limit is rejected")
  void filter_bodyOneByteOverLimit_isRejected() {
    ServerWebExchange exchange = post(Flux.just(
        DefaultDataBufferFactory.sharedInstance.wrap(new byte[LIMIT + 1])));

    StepVerifier.create(filter.filter(exchange, chain)).expectError(ResponseStatusException.class).verify();
  }

  @Test
  @DisplayName("an accepted body is cached and replayed to the downstream handler")
  void filter_acceptedBody_isCachedAndReplayed() {
    ServerWebExchange exchange = post(Flux.just(
        DefaultDataBufferFactory.sharedInstance.wrap("{\"a\":".getBytes()),
        DefaultDataBufferFactory.sharedInstance.wrap("1}".getBytes())));

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    ArgumentCaptor<ServerWebExchange> downstream = ArgumentCaptor.forClass(ServerWebExchange.class);
    verify(chain).filter(downstream.capture());
    assertThat(new String((byte[]) exchange.getAttribute("cachedRequestBody"))).isEqualTo("{\"a\":1}");
    DataBuffer replayed = DataBufferUtils.join(downstream.getValue().getRequest().getBody()).block();
    byte[] bytes = new byte[replayed.readableByteCount()];
    replayed.read(bytes);
    assertThat(new String(bytes)).isEqualTo("{\"a\":1}");
    assertThat(observationsStopped).hasValue(1);
    verify(eventLogHelper, times(1)).save(any(), any(ContextDTO.class));
  }

  @Test
  @DisplayName("an empty POST body is accepted and cached as empty")
  void filter_emptyBody_isAccepted() {
    ServerWebExchange exchange = post(Flux.empty());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    assertThat((byte[]) exchange.getAttribute("cachedRequestBody")).isEmpty();
  }

  @Test
  @DisplayName("a downstream error still closes the observation and writes the event log once")
  void filter_chainError_closesObservationAndSavesEventLog() {
    when(chain.filter(any())).thenReturn(Mono.error(new IllegalStateException("boom")));
    ServerWebExchange exchange = post(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(new byte[10])));

    StepVerifier.create(filter.filter(exchange, chain)).expectError(IllegalStateException.class).verify();

    assertThat(observationsStopped).hasValue(1);
    verify(eventLogHelper, times(1)).save(any(), any(ContextDTO.class));
  }

  @Test
  @DisplayName("a GET skips body buffering and still closes the observation")
  void filter_get_skipsBufferingAndClosesObservation() {
    ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(PATH).build());

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    assertThat((byte[]) exchange.getAttribute("cachedRequestBody")).isEmpty();
    assertThat(observationsStopped).hasValue(1);
    verify(eventLogHelper).save(any(), any(ContextDTO.class));
  }

  private static ServerWebExchange post(Flux<? extends DataBuffer> body) {
    return MockServerWebExchange.from(MockServerHttpRequest.post(PATH)
        .header("X-Request-Id", "req-1")
        .body(body));
  }
}
