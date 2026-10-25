package com.nantaaditya.example.idempotency;

import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.response.GatewayResponse;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Makes a request that carries an idempotency key hit the upstream at most once, including when several
 * requests with the same key overlap.
 *
 * <ol>
 *   <li><b>Replay.</b> A result already stored in {@code outbound_histories} within the system's idempotency
 *       TTL is returned ({@code fromCache = true}) without calling the upstream.
 *   <li><b>Per instance.</b> Overlapping calls with the same {@code (system, key)} share one execution; the
 *       others receive its result flagged as a replay.
 *   <li><b>Across instances.</b> The leader of that group reserves the key in
 *       {@code outbound_idempotency_reservations}. If another instance already holds it, this instance polls
 *       until that instance's result appears in {@code outbound_histories}, takes the key over if the
 *       reservation expires (owner crashed), and gives up with a 409 reject once a full lease has passed.
 * </ol>
 *
 * <p>The execution is started eagerly and runs to completion even if every caller disconnects: the upstream
 * call may already be on the wire, and its result must still be stored so a retry is answered from history
 * instead of calling the upstream again. The execution is expected to store the history row itself, before
 * it returns; the reservation is released only after that.
 *
 * <p>Known limits: if the instance dies after the upstream answered but before the history row was written,
 * the next caller will execute again; if an execution outlives the lease, another instance can take the key
 * over, so the lease must exceed the longest possible call (read timeout times retry attempts).
 */
@Log4j2
@Component
public class IdempotencyGuard {

  static final String IN_PROGRESS_REASON = "request with the same idempotency key is still in progress";

  private final OutboundHistoryRepository historyRepository;
  private final IdempotencyReservationRepository reservations;
  private final Duration lease;
  private final Duration pollInterval;

  private final ConcurrentMap<String, Mono<GatewayResponse>> inFlight = new ConcurrentHashMap<>();

  /**
   * apps.idempotency.lease: How long one instance may hold an idempotency key while it calls the upstream. Must exceed the longest
   * apps.idempotency.poll-interval: possible call (read timeout x retry attempts). Other instances wait up to this long, then take the key over.
   * @param historyRepository
   * @param reservations
   * @param lease
   * @param pollInterval
   */
  @Autowired
  public IdempotencyGuard(
      OutboundHistoryRepository historyRepository,
      IdempotencyReservationRepository reservations,
      @Value("${apps.idempotency.lease:120s}") Duration lease,
      @Value("${apps.idempotency.poll-interval:200ms}") Duration pollInterval) {
    this.historyRepository = historyRepository;
    this.reservations = reservations;
    this.lease = lease;
    this.pollInterval = pollInterval;
  }

  public Mono<GatewayResponse> guard(long systemId, String idempotencyKey, int ttlHours,
      Supplier<Mono<GatewayResponse>> execution) {
    return replay(systemId, idempotencyKey, ttlHours)
        .switchIfEmpty(Mono.defer(() -> shared(systemId, idempotencyKey, ttlHours, execution)));
  }

  private Mono<GatewayResponse> shared(long systemId, String key, int ttlHours,
      Supplier<Mono<GatewayResponse>> execution) {
    return Mono.deferContextual(ctx -> {
      String flowKey = systemId + ":" + key;
      Sinks.One<GatewayResponse> outcome = Sinks.one();
      Mono<GatewayResponse> result = outcome.asMono();

      Mono<GatewayResponse> running = inFlight.putIfAbsent(flowKey, result);
      if (running != null) {
        return running.map(IdempotencyGuard::asFollowerResult);
      }

      // Leader. Subscribed here, not by the caller, so a disconnecting caller cannot cancel it; the
      // caller's Reactor context (tracing, request id) is carried over explicitly.
      Instant giveUpAt = Instant.now().plus(lease);
      acquireOrWait(systemId, key, ttlHours, execution, giveUpAt)
          .contextWrite(ctx)
          .doFinally(signal -> inFlight.remove(flowKey, result))
          .subscribe(outcome::tryEmitValue, outcome::tryEmitError, outcome::tryEmitEmpty);
      return result;
    });
  }

  private Mono<GatewayResponse> acquireOrWait(long systemId, String key, int ttlHours,
      Supplier<Mono<GatewayResponse>> execution, Instant giveUpAt) {
    String owner = UUID.randomUUID().toString();
    return reservations.tryAcquire(systemId, key, owner, lease).flatMap(acquired -> {
      if (acquired) {
        return runOwned(systemId, key, ttlHours, owner, execution);
      }
      return replay(systemId, key, ttlHours).switchIfEmpty(Mono.defer(() -> {
        if (Instant.now().isAfter(giveUpAt)) {
          log.warn(AppLogMessage.message(
              "#IDEMPOTENCY - key still reserved after {}, rejecting systemId={} key={}", lease, systemId, key));
          return Mono.just(GatewayResponse.internalReject(409, IN_PROGRESS_REASON));
        }
        return Mono.delay(pollInterval)
            .then(Mono.defer(() -> acquireOrWait(systemId, key, ttlHours, execution, giveUpAt)));
      }));
    });
  }

  private Mono<GatewayResponse> runOwned(long systemId, String key, int ttlHours, String owner,
      Supplier<Mono<GatewayResponse>> execution) {
    return Mono.usingWhen(
        Mono.just(owner),
        // another instance may have stored its result between our first lookup and the reservation
        o -> replay(systemId, key, ttlHours).switchIfEmpty(Mono.defer(execution)),
        o -> reservations.release(systemId, key, o)
            .onErrorResume(error -> {
              log.warn(AppLogMessage.message("#IDEMPOTENCY - failed to release reservation systemId={} key={}: {}",
                  systemId, key, error.getMessage()).error(error));
              return Mono.empty();
            }));
  }

  private Mono<GatewayResponse> replay(long systemId, String key, int ttlHours) {
    return Mono.defer(() -> historyRepository
        .findFirstByOutboundSystemIdAndIdempotencyKeyAndCreatedDateAfterOrderByCreatedDateDesc(
            systemId, key, LocalDateTime.now().minusHours(ttlHours))
        .map(history -> GatewayResponse.fromCache(
            history.getResponseStatus(),
            Map.of(),
            history.getResponsePayload(),
            Optional.ofNullable(history.getConvertedStatus()).orElse(history.getResponseStatus()),
            Optional.ofNullable(history.getConvertedResponse()).orElse(history.getResponsePayload()))));
  }

  /** The leader's own result is returned as is; callers that merely waited for it get it as a replay. */
  private static GatewayResponse asFollowerResult(GatewayResponse leaderResult) {
    boolean inProgressReject = leaderResult.status() == 409 && IN_PROGRESS_REASON.equals(leaderResult.body());
    return inProgressReject ? leaderResult : leaderResult.asReplay();
  }
}
