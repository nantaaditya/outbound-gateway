package com.nantaaditya.example.model.request.admin;

import jakarta.validation.constraints.Min;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.validator.constraints.Range;

public record UpsertResilienceRequest(
    @Min(value = 1, message = "NotValid")
    int readTimeOutMs,
    CircuitBreaker circuitBreaker,
    BulkHead bulkHead,
    RateLimiter rateLimiter,
    Retry retry
) {

  public static record CircuitBreaker(
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      float cbFailureRateThreshold,
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      float cbSlowCallRateThreshold,
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int cbSlowCallDurationMs,
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int cbPermittedInHalfOpen,
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int cbSlidingWindowSize,
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int cbWaitDurationOpenMs
  ) {}

  public static record BulkHead(
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int bhMaxConcurrentCalls,
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int bhMaxWaitDurationMs
  ) {}

  public static record RateLimiter(
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int rlLimitForPeriod,
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int rlLimitRefreshPeriodMs,
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int rlTimeoutDurationMs
  ) {}

  public static record Retry(
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int retryMaxAttempts,
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int retryWaitDurationMs,
      Set<String> retryOnStatusCodes
  ) {
    public String toStatusCodeString() {
      if (retryOnStatusCodes == null) return null;
      return retryOnStatusCodes.stream().collect(Collectors.joining(","));
    }
  }

}
