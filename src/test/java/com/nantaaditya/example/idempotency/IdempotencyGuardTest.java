package com.nantaaditya.example.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.model.response.GatewayResponse;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class IdempotencyGuardTest {

  private static final long SYSTEM_ID = 1L;
  private static final String KEY = "k-1";
  private static final int TTL_HOURS = 24;

  @Mock private OutboundHistoryRepository historyRepository;
  @Mock private IdempotencyReservationRepository reservations;

  private IdempotencyGuard guard;
  private final AtomicInteger executions = new AtomicInteger();

  @BeforeEach
  void setUp() {
    guard = new IdempotencyGuard(historyRepository, reservations,
        Duration.ofMillis(400), Duration.ofMillis(20));
    lenient().when(reservations.release(anyLong(), anyString(), anyString())).thenReturn(Mono.empty());
  }

  @Test
  @DisplayName("a stored result is replayed without touching reservations or the upstream")
  void guard_historyHit_replaysWithoutExecuting() {
    history(row());

    StepVerifier.create(guard.guard(SYSTEM_ID, KEY, TTL_HOURS, this::execution))
        .assertNext(r -> {
          assertThat(r.fromCache()).isTrue();
          assertThat(r.status()).isEqualTo(200);
          assertThat(r.body()).isEqualTo("{\"stored\":true}");
        })
        .verifyComplete();

    assertThat(executions).hasValue(0);
    verify(reservations, never()).tryAcquire(anyLong(), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("the replay lookup uses the system's idempotency TTL as the cutoff")
  void guard_historyLookup_usesTtlCutoff() {
    history(row());
    ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);

    guard.guard(SYSTEM_ID, KEY, 2, this::execution).block();

    verify(historyRepository).findFirstByOutboundSystemIdAndIdempotencyKeyAndCreatedDateAfterOrderByCreatedDateDesc(
        eq(SYSTEM_ID), eq(KEY), cutoff.capture());
    assertThat(Duration.between(cutoff.getValue(), LocalDateTime.now().minusHours(2)).abs())
        .isLessThan(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("with no stored result and a free reservation the request runs once and the reservation is released")
  void guard_acquired_executesOnceAndReleases() {
    history((OutboundHistory) null);
    acquire(true);

    StepVerifier.create(guard.guard(SYSTEM_ID, KEY, TTL_HOURS, this::execution))
        .assertNext(r -> assertThat(r.fromCache()).isFalse())
        .verifyComplete();

    assertThat(executions).hasValue(1);
    ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
    verify(reservations).tryAcquire(eq(SYSTEM_ID), eq(KEY), owner.capture(), any(Duration.class));
    verify(reservations).release(SYSTEM_ID, KEY, owner.getValue());
  }

  @Test
  @DisplayName("a result stored between the first lookup and the reservation is replayed, not executed again")
  void guard_historyAppearsAfterAcquire_replaysAndReleases() {
    history(null, row());
    acquire(true);

    StepVerifier.create(guard.guard(SYSTEM_ID, KEY, TTL_HOURS, this::execution))
        .assertNext(r -> assertThat(r.fromCache()).isTrue())
        .verifyComplete();

    assertThat(executions).hasValue(0);
    verify(reservations).release(eq(SYSTEM_ID), eq(KEY), anyString());
  }

  @Test
  @DisplayName("overlapping calls with the same key in one instance share a single execution")
  void guard_overlappingSameKey_executesOnceAndFollowersGetReplayFlag() {
    history((OutboundHistory) null);
    acquire(true);

    List<GatewayResponse> results = Mono.zip(
            guard.guard(SYSTEM_ID, KEY, TTL_HOURS, this::slowExecution),
            guard.guard(SYSTEM_ID, KEY, TTL_HOURS, this::slowExecution),
            guard.guard(SYSTEM_ID, KEY, TTL_HOURS, this::slowExecution))
        .map(t -> List.of(t.getT1(), t.getT2(), t.getT3()))
        .block(Duration.ofSeconds(5));

    assertThat(executions).hasValue(1);
    verify(reservations, times(1)).tryAcquire(anyLong(), anyString(), anyString(), any());
    assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
    assertThat(results.stream().filter(GatewayResponse::fromCache).count()).isEqualTo(2);
  }

  @Test
  @DisplayName("different keys do not share an execution")
  void guard_differentKeys_executeIndependently() {
    lenient().when(historyRepository.findFirstByOutboundSystemIdAndIdempotencyKeyAndCreatedDateAfterOrderByCreatedDateDesc(
        anyLong(), anyString(), any(LocalDateTime.class))).thenReturn(Mono.empty());
    lenient().when(reservations.tryAcquire(anyLong(), anyString(), anyString(), any(Duration.class)))
        .thenReturn(Mono.just(true));

    Mono.zip(guard.guard(SYSTEM_ID, "a", TTL_HOURS, this::slowExecution),
            guard.guard(SYSTEM_ID, "b", TTL_HOURS, this::slowExecution))
        .block(Duration.ofSeconds(5));

    assertThat(executions).hasValue(2);
  }

  @Test
  @DisplayName("once a call has finished the key is free again: the next call starts a new flow")
  void guard_afterCompletion_inFlightEntryIsCleared() {
    history((OutboundHistory) null);
    acquire(true);

    guard.guard(SYSTEM_ID, KEY, TTL_HOURS, this::execution).block();
    guard.guard(SYSTEM_ID, KEY, TTL_HOURS, this::execution).block();

    assertThat(executions).hasValue(2);
  }

  @Test
  @DisplayName("an execution error reaches every waiting caller and the reservation is still released")
  void guard_executionError_propagatesAndReleases() {
    history((OutboundHistory) null);
    acquire(true);

    StepVerifier.create(Mono.zip(
            guard.guard(SYSTEM_ID, KEY, TTL_HOURS, () -> Mono.delay(Duration.ofMillis(50))
                .then(Mono.<GatewayResponse>error(new IllegalStateException("boom")))),
            guard.guard(SYSTEM_ID, KEY, TTL_HOURS, this::slowExecution)))
        .expectError(IllegalStateException.class)
        .verify(Duration.ofSeconds(5));

    verify(reservations, timeout(2000)).release(eq(SYSTEM_ID), eq(KEY), anyString());
  }

  @Test
  @DisplayName("when another instance holds the reservation the call waits and replays its stored result")
  void guard_heldByOtherInstance_waitsThenReplays() {
    history(null, null, row());
    acquire(false);

    StepVerifier.create(guard.guard(SYSTEM_ID, KEY, TTL_HOURS, this::execution))
        .assertNext(r -> assertThat(r.fromCache()).isTrue())
        .verifyComplete();

    assertThat(executions).hasValue(0);
    verify(reservations, never()).release(anyLong(), anyString(), anyString());
  }

  @Test
  @DisplayName("a reservation that expires (owner crashed) is taken over and the request runs here")
  void guard_reservationExpires_takesOverAndExecutes() {
    history((OutboundHistory) null);
    when(reservations.tryAcquire(eq(SYSTEM_ID), eq(KEY), anyString(), any(Duration.class)))
        .thenReturn(Mono.just(false), Mono.just(false), Mono.just(true));

    StepVerifier.create(guard.guard(SYSTEM_ID, KEY, TTL_HOURS, this::execution))
        .assertNext(r -> assertThat(r.fromCache()).isFalse())
        .verifyComplete();

    assertThat(executions).hasValue(1);
    verify(reservations).release(eq(SYSTEM_ID), eq(KEY), anyString());
  }

  @Test
  @DisplayName("if the key stays reserved past the lease the call is rejected as in progress, never executed twice")
  void guard_neverFreed_rejectsAsInProgress() {
    history((OutboundHistory) null);
    acquire(false);

    StepVerifier.create(guard.guard(SYSTEM_ID, KEY, TTL_HOURS, this::execution))
        .assertNext(r -> {
          assertThat(r.status()).isEqualTo(409);
          assertThat(r.fromCache()).isFalse();
        })
        .verifyComplete();

    assertThat(executions).hasValue(0);
  }

  @Test
  @DisplayName("a caller that disconnects does not abort the execution; the reservation is released afterwards")
  void guard_subscriberCancels_executionStillCompletesAndReleases() {
    history((OutboundHistory) null);
    acquire(true);
    AtomicBoolean finished = new AtomicBoolean();

    Disposable subscription = guard.guard(SYSTEM_ID, KEY, TTL_HOURS, () ->
        Mono.delay(Duration.ofMillis(200)).map(t -> {
          finished.set(true);
          return response();
        })).subscribe();
    verify(reservations, timeout(2000)).tryAcquire(anyLong(), anyString(), anyString(), any());
    subscription.dispose();

    verify(reservations, timeout(3000)).release(eq(SYSTEM_ID), eq(KEY), anyString());
    assertThat(finished).isTrue();
  }

  // --- helpers ---

  private Mono<GatewayResponse> execution() {
    return Mono.fromSupplier(() -> {
      executions.incrementAndGet();
      return response();
    });
  }

  private Mono<GatewayResponse> slowExecution() {
    return Mono.defer(() -> {
      executions.incrementAndGet();
      return Mono.delay(Duration.ofMillis(150)).thenReturn(response());
    });
  }

  private static GatewayResponse response() {
    return GatewayResponse.passThrough(200, Map.of(), "{\"fresh\":true}");
  }

  private static OutboundHistory row() {
    return OutboundHistory.builder()
        .outboundSystemId(SYSTEM_ID).idempotencyKey(KEY)
        .responseStatus(200).responsePayload("{\"stored\":true}")
        .convertedStatus(200).convertedResponse("{\"stored\":true}")
        .build();
  }

  /** Successive history lookups return these rows in order (null = no row); the last one repeats. */
  private void history(OutboundHistory... sequence) {
    AtomicInteger calls = new AtomicInteger();
    lenient().when(historyRepository.findFirstByOutboundSystemIdAndIdempotencyKeyAndCreatedDateAfterOrderByCreatedDateDesc(
        eq(SYSTEM_ID), eq(KEY), any(LocalDateTime.class))).thenAnswer(inv -> {
          OutboundHistory next = sequence[Math.min(calls.getAndIncrement(), sequence.length - 1)];
          return next == null ? Mono.empty() : Mono.just(next);
        });
  }

  private void acquire(boolean result) {
    lenient().when(reservations.tryAcquire(eq(SYSTEM_ID), eq(KEY), anyString(), any(Duration.class)))
        .thenReturn(Mono.just(result));
  }
}
