package com.nantaaditya.example.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.base.BaseIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

class IdempotencyReservationRepositoryTest extends BaseIntegrationTest {

  private static final Duration LEASE = Duration.ofSeconds(60);

  @Autowired private IdempotencyReservationRepository reservations;
  @Autowired private DatabaseClient databaseClient;

  @AfterEach
  void cleanup() {
    databaseClient.sql("DELETE FROM outbound_idempotency_reservations").then().block();
  }

  @Test
  @DisplayName("a free key can be acquired")
  void tryAcquire_freeKey_returnsTrue() {
    assertThat(reservations.tryAcquire(1L, "k", "owner-a", LEASE).block()).isTrue();
    assertThat(rowCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("a key held by a live reservation cannot be acquired by anyone else")
  void tryAcquire_heldByOther_returnsFalse() {
    reservations.tryAcquire(1L, "k", "owner-a", LEASE).block();

    assertThat(reservations.tryAcquire(1L, "k", "owner-b", LEASE).block()).isFalse();
    assertThat(owner(1L, "k")).isEqualTo("owner-a");
  }

  @Test
  @DisplayName("acquiring is not re-entrant: the current owner does not get a second reservation")
  void tryAcquire_sameOwnerAgain_returnsFalse() {
    reservations.tryAcquire(1L, "k", "owner-a", LEASE).block();

    assertThat(reservations.tryAcquire(1L, "k", "owner-a", LEASE).block()).isFalse();
  }

  @Test
  @DisplayName("an expired reservation (crashed owner) is taken over")
  void tryAcquire_expired_isTakenOver() {
    reservations.tryAcquire(1L, "k", "dead-instance", LEASE).block();
    expire(1L, "k");

    assertThat(reservations.tryAcquire(1L, "k", "owner-b", LEASE).block()).isTrue();
    assertThat(owner(1L, "k")).isEqualTo("owner-b");
    assertThat(rowCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("different keys and different systems do not block each other")
  void tryAcquire_differentKeyOrSystem_independent() {
    reservations.tryAcquire(1L, "k", "owner-a", LEASE).block();

    assertThat(reservations.tryAcquire(1L, "other-key", "owner-b", LEASE).block()).isTrue();
    assertThat(reservations.tryAcquire(2L, "k", "owner-c", LEASE).block()).isTrue();
    assertThat(rowCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("the lease is measured with the database clock and lies in the future")
  void tryAcquire_expiryIsInTheFuture() {
    reservations.tryAcquire(1L, "k", "owner-a", LEASE).block();

    Boolean live = databaseClient
        .sql("SELECT expires_at > NOW() + interval '30 seconds' AS live FROM outbound_idempotency_reservations")
        .map(row -> row.get("live", Boolean.class)).one().block();
    assertThat(live).isTrue();
  }

  @Test
  @DisplayName("release by the owner removes the reservation so the key can be acquired again")
  void release_byOwner_removesReservation() {
    reservations.tryAcquire(1L, "k", "owner-a", LEASE).block();

    reservations.release(1L, "k", "owner-a").block();

    assertThat(rowCount()).isZero();
    assertThat(reservations.tryAcquire(1L, "k", "owner-b", LEASE).block()).isTrue();
  }

  @Test
  @DisplayName("release by a different owner (e.g. after a takeover) leaves the reservation alone")
  void release_byOtherOwner_keepsReservation() {
    reservations.tryAcquire(1L, "k", "owner-a", LEASE).block();

    reservations.release(1L, "k", "stale-owner").block();

    assertThat(rowCount()).isEqualTo(1);
    assertThat(owner(1L, "k")).isEqualTo("owner-a");
  }

  private long rowCount() {
    return databaseClient.sql("SELECT COUNT(*) AS c FROM outbound_idempotency_reservations")
        .map(row -> row.get("c", Long.class)).one().block();
  }

  private String owner(long systemId, String key) {
    return databaseClient.sql("SELECT owner FROM outbound_idempotency_reservations "
            + "WHERE outbound_system_id = :s AND idempotency_key = :k")
        .bind("s", systemId).bind("k", key)
        .map(row -> row.get("owner", String.class)).one().block();
  }

  private void expire(long systemId, String key) {
    databaseClient.sql("UPDATE outbound_idempotency_reservations SET expires_at = NOW() - interval '1 second' "
            + "WHERE outbound_system_id = :s AND idempotency_key = :k")
        .bind("s", systemId).bind("k", key).fetch().rowsUpdated().block();
  }
}
