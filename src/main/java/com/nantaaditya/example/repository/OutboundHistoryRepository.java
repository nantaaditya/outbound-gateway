package com.nantaaditya.example.repository;

import com.nantaaditya.example.entity.OutboundHistory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface OutboundHistoryRepository extends ReactiveCrudRepository<OutboundHistory, Long> {

  // Insert history for all calls. V15 dropped the unique constraint on
  // (outbound_system_id, idempotency_key) to allow key reuse across TTL windows,
  // so ON CONFLICT is no longer valid — a plain INSERT is correct here.
  // We use a manual @Query instead of save() to avoid Spring Data's SELECT-before-INSERT
  // and to ensure consistency between idempotent and non-idempotent paths.
  @Modifying
  @Query("""
      INSERT INTO outbound_histories
        (outbound_system_id, idempotency_key, caller_service, request_id,
         request_method, request_url, request_headers, request_payload,
         response_status, response_headers, response_payload,
         duration_ms, converted_status, converted_response, retry_count, request_fingerprint, created_date)
      VALUES
        (:outboundSystemId, :idempotencyKey, :callerService, :requestId,
         :requestMethod, :requestUrl, :requestHeaders, :requestPayload,
         :responseStatus, :responseHeaders, :responsePayload,
         :durationMs, :convertedStatus, :convertedResponse, :retryCount, :requestFingerprint, NOW())
      """)
  Mono<Integer> saveHistory(
      @Param("outboundSystemId") long outboundSystemId,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("callerService") String callerService,
      @Param("requestId") String requestId,
      @Param("requestMethod") String requestMethod,
      @Param("requestUrl") String requestUrl,
      @Param("requestHeaders") Map<String, Object> requestHeaders,
      @Param("requestPayload") String requestPayload,
      @Param("responseStatus") Integer responseStatus,
      @Param("responseHeaders") Map<String, Object> responseHeaders,
      @Param("responsePayload") String responsePayload,
      @Param("durationMs") Integer durationMs,
      @Param("convertedStatus") Integer convertedStatus,
      @Param("convertedResponse") String convertedResponse,
      @Param("retryCount") int retryCount,
      @Param("requestFingerprint") String requestFingerprint);

  // Retention purge used by DELETE /internal-api/outbound_history.
  @Transactional
  Mono<Void> deleteByCreatedDateBefore(LocalDateTime date);

  default Mono<Integer> saveHistory(OutboundHistory h) {
    return saveHistory(
        h.getOutboundSystemId(), h.getIdempotencyKey(), h.getCallerService(), h.getRequestId(),
        h.getRequestMethod(), h.getRequestUrl(), h.getRequestHeaders(), h.getRequestPayload(),
        h.getResponseStatus(), h.getResponseHeaders(), h.getResponsePayload(),
        h.getDurationMs(), h.getConvertedStatus(), h.getConvertedResponse(), h.getRetryCount(),
        h.getRequestFingerprint());
  }

  // findFirst + OrderByCreatedDateDesc: tolerates duplicate rows from the async-race window
  // (two concurrent calls with the same key can both INSERT before either commits).
  // Returns the newest match; Mono throws IncorrectResultSizeDataAccessException without findFirst.
  Mono<OutboundHistory> findFirstByOutboundSystemIdAndIdempotencyKeyAndCreatedDateAfterOrderByCreatedDateDesc(
      long outboundSystemId, String idempotencyKey, LocalDateTime cutoff);

  // Used by GET /gateway/histories/{code} — no TTL window, returns the newest stored record.
  Mono<OutboundHistory> findFirstByOutboundSystemIdAndIdempotencyKeyOrderByCreatedDateDesc(
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
      @Nullable LocalDate startDate, @Nullable LocalDate endDate,
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
      @Nullable LocalDate startDate, @Nullable LocalDate endDate);
}
