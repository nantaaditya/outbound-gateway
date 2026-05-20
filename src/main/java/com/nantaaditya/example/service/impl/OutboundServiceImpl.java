package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.request.admin.CreateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpdateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpsertAuthRequest;
import com.nantaaditya.example.model.request.admin.UpsertHeadersRequest;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest;
import com.nantaaditya.example.model.request.admin.UpsertResponseMapperRequest;
import com.nantaaditya.example.model.response.OutboundMetricsResponse;
import com.nantaaditya.example.model.response.OutboundMetricsResponse.StatusBreakdown;
import com.nantaaditya.example.model.response.OutboundSystemDetailResponse;
import com.nantaaditya.example.model.response.OutboundSystemResponse;
import com.nantaaditya.example.model.response.TestConnectionResponse;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHeaderConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResponseMapperConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.OutboundService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Log4j2
@Service
@RequiredArgsConstructor
public class OutboundServiceImpl implements OutboundService {

  private final OutboundSystemRepository systemRepository;
  private final OutboundResilienceConfigurationRepository resilienceRepository;
  private final OutboundHeaderConfigurationRepository headerRepository;
  private final OutboundAuthConfigurationRepository authRepository;
  private final OutboundResponseMapperConfigurationRepository mapperRepository;
  private final GatewayServiceImpl gatewayService;
  private final EncryptionHelper encryptionHelper;
  private final ResilienceRegistryService resilienceRegistryService;
  private final MeterRegistry meterRegistry;
  private final DatabaseClient databaseClient;

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
  public Mono<OutboundSystemResponse> create(CreateOutboundSystemRequest request) {
    return systemRepository.save(OutboundSystem.create(request))
        .map(OutboundSystemResponse::from);
  }

  @Override
  public Mono<List<OutboundSystemResponse>> list() {
    return systemRepository.findAll()
        .map(OutboundSystemResponse::from)
        .collectList();
  }

  @Override
  public Mono<OutboundSystemDetailResponse> getDetail(String code) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("Outbound system not found: " + code)))
        .flatMap(system -> {
          long systemId = system.getId();
          Mono<OutboundResilienceConfiguration> resilienceMono = resilienceRepository.findByOutboundSystemId(
                  systemId)
              .defaultIfEmpty(new OutboundResilienceConfiguration());
          Mono<List<Map<String, Object>>> headersMono = headerRepository.findByOutboundSystemId(
                  systemId)
              .map(h -> h.getHeaders() != null ? h.getHeaders() : Map.<String, Object>of())
              .collectList();
          Mono<OutboundAuthConfiguration> authMono = authRepository.findByOutboundSystemId(systemId)
              .defaultIfEmpty(new OutboundAuthConfiguration());
          Mono<OutboundResponseMapperConfiguration> mapperMono = mapperRepository.findByOutboundSystemId(
                  systemId)
              .defaultIfEmpty(new OutboundResponseMapperConfiguration());

          return Mono.zip(resilienceMono, headersMono, authMono, mapperMono)
              .map(t -> OutboundSystemDetailResponse.from(
                  system, t.getT1(), t.getT2(), t.getT3(), t.getT4())
              );
        });
  }

  @Override
  public Mono<OutboundSystemResponse> update(String code, UpdateOutboundSystemRequest request) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("Outbound system not found: " + code)))
        .flatMap(system -> {
          system.update(request);
          return systemRepository.save(system);
        })
        .map(OutboundSystemResponse::from);
  }

  @Override
  public Mono<Boolean> delete(String code) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("Outbound system not found: " + code)))
        .flatMap(system -> {
          long systemId = system.getId();
          // TODO: make this as transactional
          return resilienceRepository.deleteByOutboundSystemId(systemId)
              .then(headerRepository.deleteByOutboundSystemId(systemId))
              .then(authRepository.deleteByOutboundSystemId(systemId))
              .then(mapperRepository.deleteByOutboundSystemId(systemId))
              .then(systemRepository.delete(system))
              .doOnSuccess(v -> gatewayService.evictContext(code))
              .thenReturn(Boolean.TRUE);
        });
  }

  @Override
  public Mono<Boolean> upsertResilience(String code, UpsertResilienceRequest request) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("Outbound system not found: " + code)))
        .flatMap(system -> resilienceRepository.findByOutboundSystemId(system.getId())
            .defaultIfEmpty(OutboundResilienceConfiguration.builder()
                .outboundSystemId(system.getId())
                .build())
            .flatMap(existing -> {
              existing.update(system.getId(), request);
              return resilienceRepository.save(existing);
            }))
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<Boolean> upsertHeaders(String code, UpsertHeadersRequest request) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("Outbound system not found: " + code)))
        .flatMap(system -> {
          Map<String, Object> headersAsObjectMap = new HashMap<>();
          if (request.headers() != null) {
            request.headers().forEach(headersAsObjectMap::put);
          }
          // TODO: make this as transactional
          return headerRepository.deleteByOutboundSystemId(system.getId())
              .then(headerRepository.save(OutboundHeaderConfiguration.builder()
                  .outboundSystemId(system.getId())
                  .headers(headersAsObjectMap)
                  .build()));
        })
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<Boolean> upsertAuth(String code, UpsertAuthRequest request) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("Outbound system not found: " + code)))
        .flatMap(system -> authRepository.findByOutboundSystemId(system.getId())
            .defaultIfEmpty(OutboundAuthConfiguration.builder()
                .outboundSystemId(system.getId())
                .build())
            .flatMap(existing -> {
              existing.update(system.getId(), encryptIfNotNull(request.apiKey()),
                  encryptIfNotNull(request.clientId()), encryptIfNotNull(request.clientSecret()),
                  request);
              return authRepository.save(existing);
            }))
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<Boolean> upsertResponseMapper(String code, UpsertResponseMapperRequest request) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("Outbound system not found: " + code)))
        .flatMap(system -> mapperRepository.findByOutboundSystemId(system.getId())
            .defaultIfEmpty(OutboundResponseMapperConfiguration.builder()
                .outboundSystemId(system.getId())
                .build())
            .flatMap(existing -> {
              existing.update(system.getId(), request);
              return mapperRepository.save(existing);
            }))
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<Boolean> reload(String code) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("Outbound system not found: " + code)))
        .doOnNext(system -> gatewayService.evictContext(code))
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<TestConnectionResponse> testConnection(String code) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("Outbound system not found: " + code)))
        .flatMap(system -> {
          WebClient client = WebClient.builder().build();
          return client.method(system.getHttpMethodEnum())
              .uri(system.getFullPath())
              .exchangeToMono(response -> {
                int status = response.statusCode().value();
                return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(body -> new TestConnectionResponse(status < 500, status, body));
              })
              .onErrorResume(e ->
                  Mono.just(new TestConnectionResponse(false, 0, e.getMessage()))
              );
        });
  }

  @Override
  public Mono<OutboundMetricsResponse> getMetrics(String code) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("Outbound system not found: " + code)))
        .flatMap(system -> {
          long systemId = system.getId();
          LocalDateTime cutoff = LocalDateTime.now().minusHours(1);

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
                long total = rows.stream()
                    .mapToLong(r -> toLong(r.get("cnt")))
                    .sum();
                Map<Integer, StatusBreakdown> breakdown = new HashMap<>();
                for (Map<String, Object> row : rows) {
                  int status = toInt(row.get("response_status"));
                  long cnt = toLong(row.get("cnt"));
                  double pct = total > 0 ? (cnt * 100.0 / total) : 0.0;
                  breakdown.put(status, new StatusBreakdown(cnt, pct));
                }
                return breakdown;
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

                String cbState = resolveCircuitBreakerState(code);
                long idempotencyHits = resolveIdempotencyHits(code);

                return new OutboundMetricsResponse(
                    code, "last_1h", totalCalls, successRate, errorRate,
                    breakdown, avgDuration, p90, p95, p99, cbState, idempotencyHits
                );
              });
        });
  }

  private String resolveCircuitBreakerState(String code) {
    try {
      OutboundResilienceConfiguration defaultCfg = new OutboundResilienceConfiguration();
      defaultCfg.setCbFailureRateThreshold(50f);
      defaultCfg.setCbSlowCallRateThreshold(100f);
      defaultCfg.setCbSlowCallDurationMs(60000);
      defaultCfg.setCbPermittedInHalfOpen(10);
      defaultCfg.setCbSlidingWindowSize(100);
      defaultCfg.setCbWaitDurationOpenMs(60000);
      return resilienceRegistryService.getOrCreateCircuitBreaker(code, defaultCfg)
          .getState().name();
    } catch (Exception e) {
      log.warn("Could not resolve circuit breaker state for {}: {}", code, e.getMessage());
      return "NONE";
    }
  }

  private long resolveIdempotencyHits(String code) {
    try {
      var counter = meterRegistry.find("outbound.idempotency.hits")
          .tag("code", code)
          .counter();
      return counter != null ? (long) counter.count() : 0L;
    } catch (Exception e) {
      return 0L;
    }
  }

  private byte[] encryptIfNotNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return encryptionHelper.encryptString(value);
  }

  private long toLong(Object value) {
    if (value == null) {
      return 0L;
    }
    if (value instanceof Number n) {
      return n.longValue();
    }
    try {
      return Long.parseLong(value.toString());
    } catch (Exception e) {
      return 0L;
    }
  }

  private int toInt(Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(value.toString());
    } catch (Exception e) {
      return 0;
    }
  }

  private double toDouble(Object value) {
    if (value == null) {
      return 0.0;
    }
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    try {
      return Double.parseDouble(value.toString());
    } catch (Exception e) {
      return 0.0;
    }
  }
}
