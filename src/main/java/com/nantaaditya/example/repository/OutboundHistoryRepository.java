package com.nantaaditya.example.repository;

import com.nantaaditya.example.entity.OutboundHistory;
import java.time.LocalDateTime;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface OutboundHistoryRepository extends ReactiveCrudRepository<OutboundHistory, Long> {

  Mono<OutboundHistory> findByOutboundSystemIdAndIdempotencyKeyAndCreatedDateAfter(
      long outboundSystemId, String idempotencyKey, LocalDateTime cutoff);

  // Used by GET /gateway/histories/{idempotencyKey} — no TTL window, returns the stored record.
  Mono<OutboundHistory> findByOutboundSystemIdAndIdempotencyKey(
      long outboundSystemId, String idempotencyKey);

  // systemId = 0  → no system filter (all systems)
  // status   = 0  → no status filter (HTTP statuses are always ≥ 100, so 0 is a safe sentinel)
  // null startDate/endDate → no date bound applied
  @Query("""
      SELECT * FROM outbound_histories
      WHERE (:systemId = 0 OR outbound_system_id = :systemId)
        AND (:status = 0 OR response_status = :status)
        AND (:startDate IS NULL OR created_date >= :startDate)
        AND (:endDate IS NULL OR created_date <= :endDate)
      ORDER BY created_date DESC
      LIMIT :size OFFSET :offset
      """)
  Flux<OutboundHistory> findPageByFilters(
      long systemId, int status,
      @Nullable LocalDateTime startDate, @Nullable LocalDateTime endDate,
      int size, long offset);

  @Query("""
      SELECT COUNT(*) FROM outbound_histories
      WHERE (:systemId = 0 OR outbound_system_id = :systemId)
        AND (:status = 0 OR response_status = :status)
        AND (:startDate IS NULL OR created_date >= :startDate)
        AND (:endDate IS NULL OR created_date <= :endDate)
      """)
  Mono<Long> countByFilters(
      long systemId, int status,
      @Nullable LocalDateTime startDate, @Nullable LocalDateTime endDate);
}
