package com.nantaaditya.example.model.request.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.validator.constraints.Range;

public record UpsertResilienceRequest(
    @Min(value = 1, message = "NotValid")
    int readTimeOutMs,
    @Valid CircuitBreaker circuitBreaker,
    @Valid BulkHead bulkHead,
    @Valid RateLimiter rateLimiter,
    @Valid Retry retry
) {

  public static record CircuitBreaker(
      @Range(min = 1, max = 100, message = "NotValid")
      float cbFailureRateThreshold,
      @Range(min = 1, max = 100, message = "NotValid")
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
      @Range(min = 0, max = Integer.MAX_VALUE, message = "NotValid")
      int bhMaxWaitDurationMs
  ) {}

  public static record RateLimiter(
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int rlLimitForPeriod,
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int rlLimitRefreshPeriodMs,
      @Range(min = 0, max = Integer.MAX_VALUE, message = "NotValid")
      int rlTimeoutDurationMs
  ) {}

  public static record Retry(
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int retryMaxAttempts,
      @Range(min = 1, max = Integer.MAX_VALUE, message = "NotValid")
      int retryWaitDurationMs,
      Set<@Pattern(regexp = "^[1-5][0-9]{2}$", message = "NotValid") String> retryOnStatusCodes
  ) {
    public String toStatusCodeString() {
      if (retryOnStatusCodes == null || retryOnStatusCodes.isEmpty()) return "";
      return retryOnStatusCodes.stream().collect(Collectors.joining(","));
    }
  }

}
