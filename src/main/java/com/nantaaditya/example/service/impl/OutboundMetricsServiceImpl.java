package com.nantaaditya.example.service.impl;

import static com.nantaaditya.example.helper.StringHelper.toLong;
import static com.nantaaditya.example.model.constant.ObservationConstant.PUBLIC_API;

import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.helper.StringHelper;
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.exception.OutboundSystemNotFoundException;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.response.OutboundMetricsResponse;
import com.nantaaditya.example.model.response.OutboundMetricsResponse.StatusBreakdown;
import com.nantaaditya.example.persistence.CustomQueryHelper;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.OutboundMetricsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Log4j2
@Service
@RequiredArgsConstructor
public class OutboundMetricsServiceImpl implements OutboundMetricsService {

  private final OutboundSystemRepository systemRepository;
  private final ResilienceRegistryService resilienceRegistryService;
  private final MeterRegistry meterRegistry;
  private final CustomQueryHelper customQueryHelper;

  private static final int METRICS_WINDOW_HOURS = 1;
  private static final String METRICS_WINDOW_LABEL = "last_" + METRICS_WINDOW_HOURS + "h";

  @Override
  public Mono<OutboundMetricsResponse> getMetrics(String code) {
    return requireSystem(code)
        .flatMap(system -> {
          long systemId = system.getId();
          LocalDateTime cutoff = LocalDateTime.now().minusHours(METRICS_WINDOW_HOURS);

          Mono<Map<String, Object>> aggregateMono = customQueryHelper.calculatePercentile(systemId, cutoff);
          Mono<Map<Integer, StatusBreakdown>> breakdownMono = customQueryHelper.mappingStatus(systemId, cutoff);

          return Mono.zip(aggregateMono, breakdownMono)
              .map(t -> {
                Map<String, Object> agg = t.getT1();
                Map<Integer, StatusBreakdown> breakdown = t.getT2();

                long totalCalls = toLong(agg.get("total_calls"));
                double successRate = StringHelper.toDouble(agg.get("success_rate"));
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
      Counter counter = meterRegistry.find(PUBLIC_API.getName().concat(".idempotency"))
          .tag(ConstantName.FEATURE_TAG_OBSERVABILITY, code)
          .counter();
      return Optional.ofNullable(counter)
          .map(c -> (long) c.count())
          .orElse(0L);
    } catch (Exception e) {
      log.warn(AppLogMessage.message("#OutboundMetrics - failed to resolve idempotency hits code={}", code).error(e));
      return 0L;
    }
  }

  private Mono<OutboundSystem> requireSystem(String code) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(Mono.error(new OutboundSystemNotFoundException(code)));
  }

}
