package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.model.exception.OutboundSystemNotFoundException;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.response.OutboundMetricsResponse;
import com.nantaaditya.example.model.response.OutboundMetricsResponse.StatusBreakdown;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.OutboundMetricsService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Log4j2
@Service
@RequiredArgsConstructor
public class OutboundMetricsServiceImpl implements OutboundMetricsService {

  private final OutboundSystemRepository systemRepository;
  private final ResilienceRegistryService resilienceRegistryService;
  private final MeterRegistry meterRegistry;
  private final DatabaseClient databaseClient;

  private static final int METRICS_WINDOW_HOURS = 1;
  private static final String METRICS_WINDOW_LABEL = "last_" + METRICS_WINDOW_HOURS + "h";

  private static final String CALCULATE_PERCENTILE_QUERY = """
      SELECT
        COUNT(*)                                                                                        AS total_calls,
        COALESCE(AVG(CAST(duration_ms AS DOUBLE)), 0)                                                   AS avg_duration_ms,
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

  @Override
  public Mono<OutboundMetricsResponse> getMetrics(String code) {
    return requireSystem(code)
        .flatMap(system -> {
          long systemId = system.getId();
          LocalDateTime cutoff = LocalDateTime.now().minusHours(METRICS_WINDOW_HOURS);

          Mono<Map<String, Object>> aggregateMono = databaseClient.sql(CALCULATE_PERCENTILE_QUERY)
              .bind("systemId", systemId)
              .bind("cutoff", cutoff)
              .fetch()
              .one()
              .defaultIfEmpty(Map.of());

          Mono<Map<Integer, StatusBreakdown>> breakdownMono = databaseClient.sql(MAPPING_STATUS_QUERY)
              .bind("systemId", systemId)
              .bind("cutoff", cutoff)
              .fetch()
              .all()
              .collectList()
              .map(rows -> {
                long total = rows.stream().mapToLong(r -> toLong(r.get("cnt"))).sum();
                return rows.stream().collect(Collectors.toUnmodifiableMap(
                    row -> toInt(row.get("response_status")),
                    row -> {
                      long cnt = toLong(row.get("cnt"));
                      double pct = total > 0 ? (cnt * 100.0 / total) : 0.0;
                      return new StatusBreakdown(cnt, pct);
                    }
                ));
              });

          return Mono.zip(aggregateMono, breakdownMono)
              .map(t -> {
                Map<String, Object> agg = t.getT1();
                Map<Integer, StatusBreakdown> breakdown = t.getT2();

                long totalCalls = toLong(agg.get("total_calls"));
                double successRate = toDouble(agg.get("success_rate"));
                double errorRate = totalCalls > 0 ? (1.0 - successRate) : 0.0;
                long avgDuration = toLong(agg.get("avg_duration_ms"));
                long p90 = toLong(agg.get("p90_duration_ms"));
                long p95 = toLong(agg.get("p95_duration_ms"));
                long p99 = toLong(agg.get("p99_duration_ms"));

                String cbState = resilienceRegistryService.findCircuitBreakerState(code);
                long idempotencyHits = resolveIdempotencyHits(code);

                return new OutboundMetricsResponse(
                    code, METRICS_WINDOW_LABEL, totalCalls, successRate, errorRate,
                    breakdown, avgDuration, p90, p95, p99, cbState, idempotencyHits
                );
              });
        });
  }

  private long resolveIdempotencyHits(String code) {
    try {
      var counter = meterRegistry.find("api.public.idempotency")
          .tag("feature", code)
          .counter();
      return counter != null ? (long) counter.count() : 0L;
    } catch (Exception e) {
      log.warn(AppLogMessage.message("#OutboundMetrics - failed to resolve idempotency hits code={}", code).error(e));
      return 0L;
    }
  }

  private Mono<OutboundSystem> requireSystem(String code) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(Mono.error(new OutboundSystemNotFoundException(code)));
  }

  private <T> T extractNumber(Object value, Function<Number, T> fn, T zero) {
    if (value == null) return zero;
    if (value instanceof Number n) return fn.apply(n);
    try {
      return fn.apply(Double.parseDouble(value.toString()));
    } catch (Exception ignored) {
      return zero;
    }
  }

  private long toLong(Object value) { return extractNumber(value, Number::longValue, 0L); }

  private int toInt(Object value) { return extractNumber(value, Number::intValue, 0); }

  private double toDouble(Object value) { return extractNumber(value, Number::doubleValue, 0.0); }
}
