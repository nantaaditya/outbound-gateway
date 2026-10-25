package com.nantaaditya.example.gateway;

import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.response.GatewayResponse;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Wraps an outbound call in the Resilience4j mechanisms the admin enabled for the system.
 *
 * <p>This is the single place that defines the operator order; nothing else needs to mirror it.
 * {@code transformDeferred} builds the chain inside-out, so applying the operators in the order below makes
 * the last one the outermost wrapper at execution time:
 * <pre>  RateLimiter (innermost) → Bulkhead → CircuitBreaker → Retry (outermost)</pre>
 * Retry must be outermost so each retry attempt passes through the circuit breaker, bulkhead and rate limiter
 * again. Putting Retry inside the circuit breaker would let retries bypass it, defeating its purpose.
 */
@Component
public class ResilienceDecorator {

  /**
   * Returns {@code execution} unchanged for a bypassed context (no resilience row) or when no mechanism is
   * enabled; otherwise the call wrapped in the instances the context carries (only enabled ones are set).
   */
  public Mono<GatewayResponse> decorate(OutboundSystemContext context, Mono<GatewayResponse> execution) {
    if (context.getResilience().isBypassed()) {
      return execution;
    }
    if (context.getRateLimiter() != null) {
      execution = execution.transformDeferred(RateLimiterOperator.of(context.getRateLimiter()));
    }
    if (context.getBulkhead() != null) {
      execution = execution.transformDeferred(BulkheadOperator.of(context.getBulkhead()));
    }
    if (context.getCircuitBreaker() != null) {
      execution = execution.transformDeferred(CircuitBreakerOperator.of(context.getCircuitBreaker()));
    }
    if (context.getRetry() != null) {
      execution = execution.transformDeferred(RetryOperator.of(context.getRetry()));
    }
    return execution;
  }
}
