package com.nantaaditya.example.idempotency;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Persistence for {@code outbound_idempotency_reservations}. Both operations are a single atomic
 * statement, so concurrent instances cannot both win the same key.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyReservationRepository {

  private final DatabaseClient databaseClient;

  /**
   * Reserves the key for {@code owner} for {@code lease}. Succeeds when no reservation exists or the
   * existing one has expired (its owner is assumed dead); fails when a live reservation exists, including
   * one held by {@code owner} itself.
   */
  public Mono<Boolean> tryAcquire(long systemId, String key, String owner, Duration lease) {
    return databaseClient.sql("""
            INSERT INTO outbound_idempotency_reservations AS r
                (outbound_system_id, idempotency_key, owner, expires_at)
            VALUES (:systemId, :key, :owner, NOW() + make_interval(secs => :leaseSeconds))
            ON CONFLICT (outbound_system_id, idempotency_key)
            DO UPDATE SET owner = EXCLUDED.owner, expires_at = EXCLUDED.expires_at, created_date = NOW()
            WHERE r.expires_at < NOW()
            """)
        .bind("systemId", systemId)
        .bind("key", key)
        .bind("owner", owner)
        .bind("leaseSeconds", lease.toMillis() / 1000.0)
        .fetch().rowsUpdated()
        .map(rows -> rows > 0);
  }

  /** Releases the reservation if {@code owner} still holds it; a reservation taken over meanwhile is left alone. */
  public Mono<Void> release(long systemId, String key, String owner) {
    return databaseClient.sql("""
            DELETE FROM outbound_idempotency_reservations
            WHERE outbound_system_id = :systemId AND idempotency_key = :key AND owner = :owner
            """)
        .bind("systemId", systemId)
        .bind("key", key)
        .bind("owner", owner)
        .fetch().rowsUpdated()
        .then();
  }
}
