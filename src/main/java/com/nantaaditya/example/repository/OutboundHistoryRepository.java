package com.nantaaditya.example.repository;

import com.nantaaditya.example.entity.OutboundHistory;
import java.time.LocalDateTime;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface OutboundHistoryRepository extends ReactiveCrudRepository<OutboundHistory, Long> {

  // Insert history for idempotent calls. V15 dropped the unique constraint on
  // (outbound_system_id, idempotency_key) to allow key reuse across TTL windows,
  // so ON CONFLICT is no longer valid — a plain INSERT is correct here.
  @Modifying
  @Query("""
      INSERT INTO outbound_histories
        (outbound_system_id, idempotency_key, caller_service, request_method, request_url,
         request_payload, response_status, response_payload, duration_ms,
         converted_status, converted_response, created_date)
      VALUES
        (:outboundSystemId, :idempotencyKey, :callerService, :requestMethod, :requestUrl,
         :requestPayload, :responseStatus, :responsePayload, :durationMs,
         :convertedStatus, :convertedResponse, NOW())
      """)
  Mono<Integer> upsertHistory(
      @Param("outboundSystemId") long outboundSystemId,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("callerService") String callerService,
      @Param("requestMethod") String requestMethod,
      @Param("requestUrl") String requestUrl,
      @Param("requestPayload") String requestPayload,
      @Param("responseStatus") Integer responseStatus,
      @Param("responsePayload") String responsePayload,
      @Param("durationMs") Integer durationMs,
      @Param("convertedStatus") Integer convertedStatus,
      @Param("convertedResponse") String convertedResponse);

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
