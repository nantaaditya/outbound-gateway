package com.nantaaditya.example.model.response;

import java.util.Map;

public record OutboundMetricsResponse(
    String code,
    String period,
    long totalCalls,
    double successRate,
    double errorRate,
    Map<Integer, StatusBreakdown> breakdownByStatus,
    long avgDurationMs,
    long p90DurationMs,
    long p95DurationMs,
    long p99DurationMs,
    String circuitBreakerState,
    long idempotencyHits
) {

  public record StatusBreakdown(long count, double percentage) {}
}
