package com.nantaaditya.example.helper;

import com.nantaaditya.example.entity.DeadLetterProcess;
import com.nantaaditya.example.model.response.OutboundMetricsResponse.StatusBreakdown;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class CustomQueryHelper {

  private final DatabaseClient databaseClient;
  private final TransactionalOperator transactionalOperator;

  private static final String FIND_UNPROCESSED_DEAD_LETTER_PROCESS = """
      select * from dead_letter_process
      where process_type = :processType and process_name = :processName and processed is false
      order by created_date asc
      limit :limit
      for update skip locked""";

  private static final String CALCULATE_PERCENTILE_QUERY = """
      SELECT
        COUNT(*)                                                                                        AS total_calls,
        COALESCE(AVG(CAST(duration_ms AS DOUBLE PRECISION)), 0)                                         AS avg_duration_ms,
        COALESCE(percentile_cont(0.90) WITHIN GROUP (ORDER BY duration_ms), 0)                          AS p90_duration_ms,
        COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0)                          AS p95_duration_ms,
        COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms), 0)                          AS p99_duration_ms,
        COALESCE(SUM(CASE WHEN response_status < 400 THEN 1 ELSE 0 END) * 1.0 / NULLIF(COUNT(*), 0), 0) AS success_rate
      FROM outbound_histories
      WHERE outbound_system_id = :systemId AND created_date >= :cutoff
      """;

  private static final String MAPPING_STATUS_QUERY = """
      SELECT response_status, COUNT(*) AS cnt
      FROM outbound_histories
      WHERE outbound_system_id = :systemId AND created_date >= :cutoff
      GROUP BY response_status
      """;

  public Flux<DeadLetterProcess> findUnprocessedDeadLetterProcesses(String processType, String processName, int size) {
    return databaseClient.sql(FIND_UNPROCESSED_DEAD_LETTER_PROCESS)
        .bind("processType", processType)
        .bind("processName", processName)
        .bind("limit", size)
        .map((row, metadata) -> DeadLetterProcess.from(row))
        .all()
        .as(transactionalOperator::transactional);
  }

  public Mono<Map<String, Object>> calculatePercentile(long systemId, LocalDateTime cutOff) {
    return databaseClient.sql(CALCULATE_PERCENTILE_QUERY)
        .bind("systemId", systemId)
        .bind("cutoff", cutOff)
        .fetch()
        .one()
        .defaultIfEmpty(Map.of());
  }

  public Mono<Map<Integer, StatusBreakdown>> mappingStatus(long systemId, LocalDateTime cutOff) {
    return databaseClient.sql(MAPPING_STATUS_QUERY)
        .bind("systemId", systemId)
        .bind("cutoff", cutOff)
        .fetch()
        .all()
        .collectList()
        .map(rows -> {
          long total = rows.stream().mapToLong(r -> StringHelper.toLong(r.get("cnt"))).sum();
          return rows.stream().collect(Collectors.toUnmodifiableMap(
              row -> StringHelper.toInt(row.get("response_status")),
              row -> {
                long cnt = StringHelper.toLong(row.get("cnt"));
                double pct = total > 0 ? (cnt * 100.0 / total) : 0.0;
                return new StatusBreakdown(cnt, pct);
              }
          ));
        });
  }
}
