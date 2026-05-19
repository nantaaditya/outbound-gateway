package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.model.response.GatewayResponse;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

@Log4j2
@Service
public class ResilienceRegistryService {

  private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
  private final Map<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();
  private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
  private final Map<String, Retry> retries = new ConcurrentHashMap<>();

  public CircuitBreaker getOrCreateCircuitBreaker(String name, OutboundResilienceConfiguration config) {
    return circuitBreakers.computeIfAbsent(name, k -> buildCircuitBreaker(k, config));
  }

  public Bulkhead getOrCreateBulkhead(String name, OutboundResilienceConfiguration config) {
    return bulkheads.computeIfAbsent(name, k -> buildBulkhead(k, config));
  }

  public RateLimiter getOrCreateRateLimiter(String name, OutboundResilienceConfiguration config) {
    return rateLimiters.computeIfAbsent(name, k -> buildRateLimiter(k, config));
  }

  public Retry getOrCreateRetry(String name, OutboundResilienceConfiguration config) {
    return retries.computeIfAbsent(name, k -> buildRetry(k, config));
  }

  public void reload(String name) {
    circuitBreakers.remove(name);
    bulkheads.remove(name);
    rateLimiters.remove(name);
    retries.remove(name);
  }

  private CircuitBreaker buildCircuitBreaker(String name, OutboundResilienceConfiguration cfg) {
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .failureRateThreshold(cfg.getCbFailureRateThreshold())
        .slowCallRateThreshold(cfg.getCbSlowCallRateThreshold())
        .slowCallDurationThreshold(Duration.ofMillis(cfg.getCbSlowCallDurationMs()))
        .permittedNumberOfCallsInHalfOpenState(cfg.getCbPermittedInHalfOpen())
        .slidingWindowSize(cfg.getCbSlidingWindowSize())
        .waitDurationInOpenState(Duration.ofMillis(cfg.getCbWaitDurationOpenMs()))
        .build();
    return CircuitBreakerRegistry.of(config).circuitBreaker(name);
  }

  private Bulkhead buildBulkhead(String name, OutboundResilienceConfiguration cfg) {
    BulkheadConfig config = BulkheadConfig.custom()
        .maxConcurrentCalls(cfg.getBhMaxConcurrentCalls())
        .maxWaitDuration(Duration.ofMillis(cfg.getBhMaxWaitDurationMs()))
        .build();
    return BulkheadRegistry.of(config).bulkhead(name);
  }

  private RateLimiter buildRateLimiter(String name, OutboundResilienceConfiguration cfg) {
    RateLimiterConfig config = RateLimiterConfig.custom()
        .limitForPeriod(cfg.getRlLimitForPeriod())
        .limitRefreshPeriod(Duration.ofMillis(cfg.getRlLimitRefreshPeriodMs()))
        .timeoutDuration(Duration.ofMillis(cfg.getRlTimeoutDurationMs()))
        .build();
    return RateLimiterRegistry.of(config).rateLimiter(name);
  }

  private Retry buildRetry(String name, OutboundResilienceConfiguration cfg) {
    Set<Integer> retryStatuses = parseStatusCodes(cfg.getRetryOnStatusCodes());
    RetryConfig config = RetryConfig.custom()
        .maxAttempts(cfg.getRetryMaxAttempts())
        .waitDuration(Duration.ofMillis(cfg.getRetryWaitDurationMs()))
        .retryOnResult(response -> {
          if (response instanceof GatewayResponse r) {
            return retryStatuses.contains(r.status());
          }
          return false;
        })
        .build();
    return RetryRegistry.of(config).retry(name);
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
        log.warn("Skipping unparseable retry status code: '{}'", s);
      }
    }
    return result;
  }
}
