package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.model.response.GatewayResponse;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import com.nantaaditya.example.model.logger.AppLogMessage;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

@Log4j2
@Service
public class ResilienceRegistryService {

  private final CircuitBreakerRegistry circuitBreakerRegistry;
  private final BulkheadRegistry bulkheadRegistry;
  private final RateLimiterRegistry rateLimiterRegistry;
  private final RetryRegistry retryRegistry;

  public ResilienceRegistryService(MeterRegistry meterRegistry) {
    this.circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    this.bulkheadRegistry = BulkheadRegistry.ofDefaults();
    this.rateLimiterRegistry = RateLimiterRegistry.ofDefaults();
    this.retryRegistry = RetryRegistry.ofDefaults();

    // Bind all resilience4j registries to Micrometer so every instance created
    // via getOrCreate* is automatically tracked without manual registration.
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry).bindTo(meterRegistry);
    TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheadRegistry).bindTo(meterRegistry);
    TaggedRateLimiterMetrics.ofRateLimiterRegistry(rateLimiterRegistry).bindTo(meterRegistry);
    TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(meterRegistry);
  }

  public CircuitBreaker getOrCreateCircuitBreaker(String name, OutboundResilienceConfiguration config) {
    return circuitBreakerRegistry.circuitBreaker(name, buildCbConfig(config));
  }

  public Bulkhead getOrCreateBulkhead(String name, OutboundResilienceConfiguration config) {
    return bulkheadRegistry.bulkhead(name, buildBhConfig(config));
  }

  public RateLimiter getOrCreateRateLimiter(String name, OutboundResilienceConfiguration config) {
    return rateLimiterRegistry.rateLimiter(name, buildRlConfig(config));
  }

  public Retry getOrCreateRetry(String name, OutboundResilienceConfiguration config) {
    return retryRegistry.retry(name, buildRetryConfig(config));
  }

  public void reload(String name) {
    circuitBreakerRegistry.remove(name);
    bulkheadRegistry.remove(name);
    rateLimiterRegistry.remove(name);
    retryRegistry.remove(name);
  }

  public String findCircuitBreakerState(String name) {
    return circuitBreakerRegistry.find(name)
        .map(cb -> cb.getState().name())
        .orElse("NONE");
  }

  private CircuitBreakerConfig buildCbConfig(OutboundResilienceConfiguration cfg) {
    return CircuitBreakerConfig.custom()
        .failureRateThreshold(cfg.getCbFailureRateThreshold())
        .slowCallRateThreshold(cfg.getCbSlowCallRateThreshold())
        .slowCallDurationThreshold(Duration.ofMillis(cfg.getCbSlowCallDurationMs()))
        .permittedNumberOfCallsInHalfOpenState(cfg.getCbPermittedInHalfOpen())
        .slidingWindowSize(cfg.getCbSlidingWindowSize())
        .waitDurationInOpenState(Duration.ofMillis(cfg.getCbWaitDurationOpenMs()))
        .build();
  }

  private BulkheadConfig buildBhConfig(OutboundResilienceConfiguration cfg) {
    return BulkheadConfig.custom()
        .maxConcurrentCalls(cfg.getBhMaxConcurrentCalls())
        .maxWaitDuration(Duration.ofMillis(cfg.getBhMaxWaitDurationMs()))
        .build();
  }

  private RateLimiterConfig buildRlConfig(OutboundResilienceConfiguration cfg) {
    return RateLimiterConfig.custom()
        .limitForPeriod(cfg.getRlLimitForPeriod())
        .limitRefreshPeriod(Duration.ofMillis(cfg.getRlLimitRefreshPeriodMs()))
        .timeoutDuration(Duration.ofMillis(cfg.getRlTimeoutDurationMs()))
        .build();
  }

  private RetryConfig buildRetryConfig(OutboundResilienceConfiguration cfg) {
    Set<Integer> retryStatuses = parseStatusCodes(cfg.getRetryOnStatusCodes());
    return RetryConfig.custom()
        .maxAttempts(cfg.getRetryMaxAttempts())
        .waitDuration(Duration.ofMillis(cfg.getRetryWaitDurationMs()))
        .retryOnResult(response -> {
          if (response instanceof GatewayResponse r) {
            return retryStatuses.contains(r.status());
          }
          return false;
        })
        .failAfterMaxAttempts(true)
        .build();
  }

  private Set<Integer> parseStatusCodes(String codes) {
    if (codes == null || codes.isBlank()) return Set.of();
    Set<Integer> result = new HashSet<>();
    for (String part : codes.split(",")) {
      String s = part.trim();
      if (s.isEmpty()) continue;
      try {
        result.add(Integer.parseInt(s));
      } catch (NumberFormatException e) {
        log.warn(AppLogMessage.message("#ResilienceRegistry - unparseable retry status code '{}'", s));
      }
    }
    return result;
  }
}
