package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.logger.AppLogMessage;
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
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResponseMapperConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.model.exception.OutboundSystemNotFoundException;
import com.nantaaditya.example.service.GatewayService;
import com.nantaaditya.example.service.OutboundService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Log4j2
@Service
@RequiredArgsConstructor
public class OutboundServiceImpl implements OutboundService {

  private final OutboundSystemRepository systemRepository;
  private final OutboundResilienceConfigurationRepository resilienceRepository;
  private final OutboundHeaderConfigurationRepository headerRepository;
  private final OutboundAuthConfigurationRepository authRepository;
  private final OutboundResponseMapperConfigurationRepository mapperRepository;
  private final OutboundHostConfigurationRepository hostConfigRepository;
  private final GatewayService gatewayService;
  private final GatewayWebClientBuilder contextBuilder;
  private final EncryptionHelper encryptionHelper;
  private final ResilienceRegistryService resilienceRegistryService;
  private final TransactionalOperator transactionalOperator;
  private final MeterRegistry meterRegistry;
  private final DatabaseClient databaseClient;

  private static final Duration TEST_CONNECTION_TIMEOUT = Duration.ofSeconds(5);
  private static final int MAX_LIST_SIZE = 1000;
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
  public Mono<OutboundSystemResponse> create(CreateOutboundSystemRequest request) {
    return systemRepository.save(OutboundSystem.create(request))
        .doOnSuccess(s -> log.info(AppLogMessage.message("#OutboundService - created outbound system code={}", s.getCode())))
        .map(OutboundSystemResponse::from);
  }

  @Override
  public Mono<List<OutboundSystemResponse>> list() {
    return systemRepository.findAll()
        .take(MAX_LIST_SIZE)
        .map(OutboundSystemResponse::from)
        .collectList();
  }

  @Override
  public Mono<OutboundSystemDetailResponse> getDetail(String code) {
    return requireSystem(code)
        .flatMap(system -> {
          long systemId = system.getId();
          Mono<Optional<OutboundResilienceConfiguration>> resilienceMono = resilienceRepository
              .findByOutboundSystemId(systemId)
              .map(Optional::of)
              .defaultIfEmpty(Optional.empty());
          Mono<List<Map<String, Object>>> headersMono = headerRepository.findByOutboundSystemId(systemId)
              .map(h -> h.getHeaders() != null ? h.getHeaders() : Map.<String, Object>of())
              .collectList();
          Mono<Optional<OutboundAuthConfiguration>> authMono = authRepository
              .findByOutboundSystemId(systemId)
              .map(Optional::of)
              .defaultIfEmpty(Optional.empty());
          Mono<Optional<OutboundResponseMapperConfiguration>> mapperMono = mapperRepository
              .findByOutboundSystemId(systemId)
              .map(Optional::of)
              .defaultIfEmpty(Optional.empty());

          return Mono.zip(resilienceMono, headersMono, authMono, mapperMono)
              .map(t -> OutboundSystemDetailResponse.from(
                  system,
                  t.getT1().orElse(null),
                  t.getT2(),
                  t.getT3().orElse(null),
                  t.getT4().orElse(null))
              );
        });
  }

  @Override
  public Mono<OutboundSystemResponse> update(String code, UpdateOutboundSystemRequest request) {
    return requireSystem(code)
        .flatMap(system -> {
          system.update(request);
          return systemRepository.save(system);
        })
        .doOnSuccess(s -> log.info(AppLogMessage.message("#OutboundService - updated outbound system code={}", code)))
        .map(OutboundSystemResponse::from);
  }

  @Override
  public Mono<Boolean> delete(String code) {
    return requireSystem(code)
        .flatMap(system -> {
          long systemId = system.getId();
          return resilienceRepository.deleteByOutboundSystemId(systemId)
              .then(headerRepository.deleteByOutboundSystemId(systemId))
              .then(authRepository.deleteByOutboundSystemId(systemId))
              .then(mapperRepository.deleteByOutboundSystemId(systemId))
              .then(systemRepository.delete(system))
              .thenReturn(Boolean.TRUE)
              .as(transactionalOperator::transactional)
              .doOnSuccess(v -> {
                gatewayService.evictContext(code);
                resilienceRegistryService.reload(code);
                log.info(AppLogMessage.message("#OutboundService - deleted outbound system code={}", code));
              });
        });
  }

  @Override
  public Mono<Boolean> upsertResilience(String code, UpsertResilienceRequest request) {
    return requireSystem(code)
        .flatMap(system -> resilienceRepository.findByOutboundSystemId(system.getId())
            .defaultIfEmpty(OutboundResilienceConfiguration.builder()
                .outboundSystemId(system.getId())
                .build())
            .flatMap(existing -> {
              existing.update(system.getId(), request);
              return resilienceRepository.save(existing);
            }))
        .doOnSuccess(s -> {
          resilienceRegistryService.reload(code);
          gatewayService.evictContext(code);
          log.info(AppLogMessage.message("#OutboundService - upserted resilience config code={}", code));
        })
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<Boolean> upsertHeaders(String code, UpsertHeadersRequest request) {
    return requireSystem(code)
        .flatMap(system -> {
          Map<String, Object> headersAsObjectMap = new HashMap<>();
          if (request.headers() != null) {
            request.headers().forEach(headersAsObjectMap::put);
          }
          return headerRepository.deleteByOutboundSystemId(system.getId())
              .then(headerRepository.save(OutboundHeaderConfiguration.builder()
                  .outboundSystemId(system.getId())
                  .headers(headersAsObjectMap)
                  .build()))
              .doOnSuccess(s -> {
                gatewayService.evictContext(code);
                log.info(AppLogMessage.message("#OutboundService - upserted headers code={}", code));
              })
              .as(transactionalOperator::transactional);
        })
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<Boolean> upsertAuth(String code, UpsertAuthRequest request) {
    return requireSystem(code)
        .flatMap(system ->
            Mono.fromCallable(() -> new byte[][]{
                    encryptIfNotNull(request.apiKey()),
                    encryptIfNotNull(request.clientId()),
                    encryptIfNotNull(request.clientSecret())
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(encrypted ->
                    authRepository.findByOutboundSystemId(system.getId())
                        .defaultIfEmpty(OutboundAuthConfiguration.builder()
                            .outboundSystemId(system.getId())
                            .build())
                        .flatMap(existing -> {
                          existing.update(system.getId(), encrypted[0], encrypted[1], encrypted[2], request);
                          return authRepository.save(existing);
                        })
                        .doOnSuccess(s -> {
                          gatewayService.evictContext(code);
                          log.info(AppLogMessage.message("#OutboundService - upserted auth config code={}", code));
                        })
                )
        )
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<Boolean> upsertResponseMapper(String code, UpsertResponseMapperRequest request) {
    return requireSystem(code)
        .flatMap(system -> mapperRepository.findByOutboundSystemId(system.getId())
            .defaultIfEmpty(OutboundResponseMapperConfiguration.builder()
                .outboundSystemId(system.getId())
                .build())
            .flatMap(existing -> {
              existing.update(system.getId(), request);
              return mapperRepository.save(existing);
            }))
        .doOnSuccess(s -> {
          gatewayService.evictContext(code);
          log.info(AppLogMessage.message("#OutboundService - upserted response mapper code={}", code));
        })
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<Boolean> reload(String code) {
    return requireSystem(code)
        .flatMap(system -> Mono.fromRunnable(() -> {
          gatewayService.evictContext(code);
          resilienceRegistryService.reload(code);
          log.info(AppLogMessage.message("#OutboundService - reloaded outbound system code={}", code));
        }))
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<TestConnectionResponse> testConnection(String code) {
    return requireSystem(code)
        .flatMap(system ->
            hostConfigRepository.findByBaseUrl(system.getBaseUrl())
                .defaultIfEmpty(defaultHostConfig(system.getBaseUrl()))
                .flatMap(hostConfig -> {
                  var client = contextBuilder.getOrCreateWebClient(hostConfig);
                  return client.method(system.getHttpMethodEnum())
                      .uri(system.getFullPath())
                      .exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new TestConnectionResponse(status < 500, status, body));
                      })
                      .timeout(TEST_CONNECTION_TIMEOUT)
                      .doOnSuccess(r -> log.info(AppLogMessage.message("#OutboundService - testConnection code={} httpStatus={} success={}", code, r.httpStatus(), r.success())))
                      .onErrorResume(e -> {
                        log.warn(AppLogMessage.message("#OutboundService - testConnection failed code={} error={}", code, e.getClass().getSimpleName()).error(e));
                        return Mono.just(new TestConnectionResponse(false, 0, "Connection failed: " + e.getClass().getSimpleName()));
                      });
                })
        );
  }

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

                String cbState = resilienceRegistryService.findCircuitBreakerState(code);
                long idempotencyHits = resolveIdempotencyHits(code);

                return new OutboundMetricsResponse(
                    code, METRICS_WINDOW_LABEL, totalCalls, successRate, errorRate,
                    breakdown, avgDuration, p90, p95, p99, cbState, idempotencyHits
                );
              });
        });
  }

  private Mono<OutboundSystem> requireSystem(String code) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(Mono.error(new OutboundSystemNotFoundException(code)));
  }

  private OutboundHostConfiguration defaultHostConfig(String baseUrl) {
    return OutboundHostConfiguration.testDefault(baseUrl);
  }

  private long resolveIdempotencyHits(String code) {
    try {
      var counter = meterRegistry.find("outbound.idempotency.hits")
          .tag("code", code)
          .counter();
      return counter != null ? (long) counter.count() : 0L;
    } catch (Exception e) {
      log.warn(AppLogMessage.message("#OutboundService - failed to resolve idempotency hits code={}", code).error(e));
      return 0L;
    }
  }

  private byte[] encryptIfNotNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return encryptionHelper.encryptString(value);
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
