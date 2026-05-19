package com.nantaaditya.example.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Resilience configuration for a single outbound system.
 *
 * <p><b>Two-level bypass model — engineers must understand this:</b>
 * <ol>
 *   <li>{@code isBypassed() == true} — no row in DB. Created by {@link #bypass()} factory.
 *       All operators (CB/Bulkhead/RateLimiter/Retry) are skipped; readTimeOutMs is also
 *       0 so timeout is skipped too (see GatewayServiceImpl.executeHttp).
 *   <li>{@code isBypassed() == false} — a row exists. Each mechanism is individually
 *       gated by its own boolean flag. All flags=false means only readTimeOutMs is
 *       enforced — no CB/BH/RL/Retry. This is a valid configuration (e.g., raw passthrough
 *       with a bounded read timeout).
 * </ol>
 *
 * <p><b>Cross-table invariants (admin responsibility):</b>
 * <ul>
 *   <li>{@code outbound_host_configurations.pending_acquire_timeout_ms} must be LESS THAN
 *       {@code read_time_out_ms} for all systems on that host. If not, pool exhaustion
 *       surfaces as a read timeout instead of a 503, making diagnosis harder.
 *   <li>{@code outbound_host_configurations.max_connections} must be GE the SUM of
 *       {@code bh_max_concurrent_calls} across all systems sharing that host. If the pool
 *       is smaller than the total bulkhead capacity, the pool becomes the de-facto
 *       bulkhead and bulkhead metrics become misleading.
 * </ul>
 */
@Data
@Table(name = "outbound_resilience_configurations")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@SuppressWarnings("java:S1068")
public class OutboundResilienceConfiguration extends BaseEntity<Long> {
  private long outboundSystemId;
  private int readTimeOutMs;
  @Transient
  private boolean bypassed;
  private float cbFailureRateThreshold;
  private float cbSlowCallRateThreshold;
  private int cbSlowCallDurationMs;
  private int cbPermittedInHalfOpen;
  private int cbSlidingWindowSize;
  private int cbWaitDurationOpenMs;
  private int bhMaxConcurrentCalls;
  private int bhMaxWaitDurationMs;
  private int rlLimitForPeriod;
  private int rlLimitRefreshPeriodMs;
  private int rlTimeoutDurationMs;
  private int retryMaxAttempts;
  private int retryWaitDurationMs;
  private String retryOnStatusCodes;

  // Each mechanism is opt-in. Admin must explicitly set these to true.
  // DEFAULT false ensures no mechanism is silently applied with default thresholds after V18 migration.
  private boolean circuitBreakerEnabled;
  private boolean bulkheadEnabled;
  private boolean rateLimiterEnabled;
  private boolean retryEnabled;

  public static OutboundResilienceConfiguration bypass() {
    OutboundResilienceConfiguration cfg = new OutboundResilienceConfiguration();
    cfg.bypassed = true;
    return cfg;
  }
}
