package com.nantaaditya.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.response.GatewayResponse;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ResilienceDecoratorTest {

  private final ResilienceDecorator decorator = new ResilienceDecorator();
  private final AtomicInteger executions = new AtomicInteger();

  private CircuitBreaker circuitBreaker;
  private Retry retry;

  @BeforeEach
  void setUp() {
    // never opens by itself in these tests (needs 100 calls), so only explicit transitions matter
    circuitBreaker = CircuitBreaker.of("cb", CircuitBreakerConfig.custom()
        .slidingWindowSize(100).minimumNumberOfCalls(100).build());
    retry = Retry.of("retry", RetryConfig.custom()
        .maxAttempts(3).waitDuration(Duration.ofMillis(1)).build());
  }

  @Test
  @DisplayName("a bypassed context (no resilience row) returns the execution untouched")
  void decorate_bypassed_returnsSameExecution() {
    Mono<GatewayResponse> execution = succeeding();
    OutboundSystemContext context = OutboundSystemContext.builder()
        .resilience(OutboundResilienceConfiguration.bypass())
        .circuitBreaker(circuitBreaker).retry(retry)   // would apply if the context were not bypassed
        .build();

    assertThat(decorator.decorate(context, execution)).isSameAs(execution);
  }

  @Test
  @DisplayName("a resilience row with every mechanism disabled returns the execution untouched")
  void decorate_noMechanismEnabled_returnsSameExecution() {
    Mono<GatewayResponse> execution = succeeding();

    assertThat(decorator.decorate(contextWith(null, null, null, null), execution)).isSameAs(execution);
  }

  @Test
  @DisplayName("a successful call passes through every enabled mechanism and runs once")
  void decorate_allMechanisms_successRunsOnce() {
    OutboundSystemContext context = contextWith(
        RateLimiter.of("rl", RateLimiterConfig.custom().limitForPeriod(10)
            .limitRefreshPeriod(Duration.ofMinutes(1)).timeoutDuration(Duration.ZERO).build()),
        Bulkhead.of("bh", BulkheadConfig.custom().maxConcurrentCalls(5).build()),
        circuitBreaker, retry);

    StepVerifier.create(decorator.decorate(context, succeeding()))
        .assertNext(r -> assertThat(r.status()).isEqualTo(200))
        .verifyComplete();

    assertThat(executions).hasValue(1);
  }

  @Test
  @DisplayName("retry re-runs a failing call until it succeeds")
  void decorate_retry_retriesUntilSuccess() {
    AtomicInteger calls = new AtomicInteger();
    Mono<GatewayResponse> flaky = Mono.defer(() -> {
      executions.incrementAndGet();
      return calls.incrementAndGet() < 3
          ? Mono.error(new IllegalStateException("boom"))
          : Mono.just(GatewayResponse.passThrough(200, Map.of(), "ok"));
    });

    StepVerifier.create(decorator.decorate(contextWith(null, null, null, retry), flaky))
        .assertNext(r -> assertThat(r.body()).isEqualTo("ok"))
        .verifyComplete();

    assertThat(executions).hasValue(3);
  }

  @Test
  @DisplayName("retry is the outermost mechanism: every attempt passes through the circuit breaker again")
  void decorate_retryOutermost_everyAttemptCountedByCircuitBreaker() {
    StepVerifier.create(decorator.decorate(contextWith(null, null, circuitBreaker, retry), failing()))
        .expectError(IllegalStateException.class)
        .verify();

    assertThat(executions).hasValue(3);
    // had Retry been placed inside the circuit breaker this would be 1
    assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
  }

  @Test
  @DisplayName("an open circuit rejects without running the call, retries included")
  void decorate_openCircuit_rejectsWithoutExecuting() {
    circuitBreaker.transitionToOpenState();

    StepVerifier.create(decorator.decorate(contextWith(null, null, circuitBreaker, retry), succeeding()))
        .expectError(CallNotPermittedException.class)
        .verify();

    assertThat(executions).hasValue(0);
  }

  @Test
  @DisplayName("rate limiter is the innermost mechanism: each retry attempt consumes a permit")
  void decorate_rateLimiterInnermost_eachAttemptConsumesPermit() {
    RateLimiter rateLimiter = RateLimiter.of("rl", RateLimiterConfig.custom()
        .limitForPeriod(2).limitRefreshPeriod(Duration.ofMinutes(1)).timeoutDuration(Duration.ZERO).build());

    StepVerifier.create(decorator.decorate(contextWith(rateLimiter, null, null, retry), failing()))
        .expectError(RequestNotPermitted.class)
        .verify();

    // attempts 1 and 2 ran; attempt 3 was refused by the rate limiter before reaching the call
    assertThat(executions).hasValue(2);
  }

  @Test
  @DisplayName("a rate limiter without a permit rejects the call")
  void decorate_rateLimiterExhausted_rejects() {
    RateLimiter rateLimiter = RateLimiter.of("rl", RateLimiterConfig.custom()
        .limitForPeriod(1).limitRefreshPeriod(Duration.ofMinutes(1)).timeoutDuration(Duration.ZERO).build());
    OutboundSystemContext context = contextWith(rateLimiter, null, null, null);

    decorator.decorate(context, succeeding()).block();

    StepVerifier.create(decorator.decorate(context, succeeding()))
        .expectError(RequestNotPermitted.class)
        .verify();
    assertThat(executions).hasValue(1);
  }

  @Test
  @DisplayName("a full bulkhead rejects the call")
  void decorate_bulkheadFull_rejects() {
    Bulkhead bulkhead = Bulkhead.of("bh", BulkheadConfig.custom()
        .maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
    OutboundSystemContext context = contextWith(null, bulkhead, null, null);

    // occupies the single slot until disposed
    Disposable occupant = decorator.decorate(context, Mono.never()).subscribe();
    try {
      StepVerifier.create(decorator.decorate(context, succeeding()))
          .expectError(BulkheadFullException.class)
          .verify();
      assertThat(executions).hasValue(0);
    } finally {
      occupant.dispose();
    }
  }

  // --- helpers ---

  private Mono<GatewayResponse> succeeding() {
    return Mono.defer(() -> {
      executions.incrementAndGet();
      return Mono.just(GatewayResponse.passThrough(200, Map.of(), "ok"));
    });
  }

  private Mono<GatewayResponse> failing() {
    return Mono.defer(() -> {
      executions.incrementAndGet();
      return Mono.error(new IllegalStateException("boom"));
    });
  }

  /** A context whose resilience row exists (not bypassed) with the given instances attached. */
  private static OutboundSystemContext contextWith(RateLimiter rateLimiter, Bulkhead bulkhead,
      CircuitBreaker circuitBreaker, Retry retry) {
    return OutboundSystemContext.builder()
        .resilience(OutboundResilienceConfiguration.builder().readTimeOutMs(1000).build())
        .rateLimiter(rateLimiter)
        .bulkhead(bulkhead)
        .circuitBreaker(circuitBreaker)
        .retry(retry)
        .build();
  }
}
