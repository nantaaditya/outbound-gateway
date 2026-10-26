package com.nantaaditya.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.cache.JsltResponseMapper;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.response.GatewayResponse;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.MaxRetriesExceededException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;

/** The status a caller sees for each way an outbound call can fail inside the gateway. */
class GatewayHelperRejectTest {

  private final GatewayHelper helper = new GatewayHelper(mock(JsonHelper.class), mock(JsltResponseMapper.class));
  private final OutboundSystemContext context = OutboundSystemContext.builder()
      .system(OutboundSystem.builder().code("pay").build())
      .build();

  @Test
  @DisplayName("a full bulkhead is 503")
  void bulkheadFull_is503() {
    assertReject(BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("bh")),
        503, "service unavailable - bulkhead full");
  }

  @Test
  @DisplayName("an open circuit is 503")
  void circuitOpen_is503() {
    assertReject(CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("cb")),
        503, "service unavailable - circuit open");
  }

  @Test
  @DisplayName("an exhausted rate limiter is 429")
  void rateLimited_is429() {
    assertReject(RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("rl")),
        429, "too many requests - rate limited");
  }

  @Test
  @DisplayName("exhausted retries are 502")
  void maxRetries_is502() {
    // the constructor is not public; a mock needs an (empty) stack trace because the mapping logs it
    MaxRetriesExceededException exhausted = mock(MaxRetriesExceededException.class);
    when(exhausted.getStackTrace()).thenReturn(new StackTraceElement[0]);

    assertReject(exhausted, 502, "service unavailable - max retries exceeded");
  }

  @Test
  @DisplayName("a read timeout is 504")
  void timeout_is504() {
    assertReject(new TimeoutException("timed out"), 504, "gateway timeout");
  }

  @Test
  @DisplayName("an upstream response larger than the configured limit is 502 and says so, not a generic 503")
  void responseTooLarge_is502() {
    GatewayResponse response = helper.toInternalRejectResponse(context,
        new DataBufferLimitException("Exceeded limit on max bytes to buffer : 262144"));

    assertThat(response.status()).isEqualTo(502);
    assertThat(response.body()).isEqualTo("bad gateway - upstream response too large");
  }

  @Test
  @DisplayName("the size limit is recognised even when the framework wraps the exception")
  void responseTooLarge_wrappedInCause_is502() {
    GatewayResponse response = helper.toInternalRejectResponse(context,
        new IllegalStateException("decoding failed",
            new DataBufferLimitException("Exceeded limit on max bytes to buffer : 262144")));

    assertThat(response.status()).isEqualTo(502);
    assertThat(response.body()).isEqualTo("bad gateway - upstream response too large");
  }

  @Test
  @DisplayName("anything else is a generic 503 internal gateway error")
  void unexpected_is503() {
    assertReject(new IllegalStateException("connection refused"), 503, "internal gateway error");
  }

  private void assertReject(Throwable error, int status, String reason) {
    GatewayResponse response = helper.toInternalRejectResponse(context, error);

    assertThat(response.status()).isEqualTo(status);
    assertThat(response.body()).isEqualTo(reason);
    assertThat(response.fromCache()).isFalse();
  }
}
