package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.service.impl.ResilienceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResilienceRegistryServiceTest {

  private ResilienceRegistryService service;

  @BeforeEach
  void setUp() {
    service = new ResilienceRegistryService();
  }

  private OutboundResilienceConfiguration defaultConfig() {
    return OutboundResilienceConfiguration.builder()
        .cbFailureRateThreshold(50f)
        .cbSlowCallRateThreshold(100f)
        .cbSlowCallDurationMs(60000)
        .cbPermittedInHalfOpen(10)
        .cbSlidingWindowSize(100)
        .cbWaitDurationOpenMs(60000)
        .bhMaxConcurrentCalls(25)
        .bhMaxWaitDurationMs(0)
        .rlLimitForPeriod(100)
        .rlLimitRefreshPeriodMs(1000)
        .rlTimeoutDurationMs(0)
        .retryMaxAttempts(3)
        .retryWaitDurationMs(500)
        .retryOnStatusCodes("500,502,503")
        .build();
  }

  @Test
  @DisplayName("getOrCreateCircuitBreaker creates a circuit breaker for new system")
  void getOrCreate_newSystem_createsCircuitBreaker() {
    var cb = service.getOrCreateCircuitBreaker("xendit", defaultConfig());

    assertThat(cb).isNotNull();
    assertThat(cb.getName()).isEqualTo("xendit");
  }

  @Test
  @DisplayName("getOrCreateCircuitBreaker returns same instance for same system code")
  void getOrCreate_sameCode_returnsSameInstance() {
    var config = defaultConfig();

    var cb1 = service.getOrCreateCircuitBreaker("xendit", config);
    var cb2 = service.getOrCreateCircuitBreaker("xendit", config);

    assertThat(cb1).isSameAs(cb2);
  }

  @Test
  @DisplayName("getOrCreateBulkhead creates a bulkhead for new system")
  void getOrCreate_newSystem_createsBulkhead() {
    var bh = service.getOrCreateBulkhead("midtrans", defaultConfig());

    assertThat(bh).isNotNull();
    assertThat(bh.getName()).isEqualTo("midtrans");
  }

  @Test
  @DisplayName("getOrCreateRateLimiter creates a rate limiter for new system")
  void getOrCreate_newSystem_createsRateLimiter() {
    var rl = service.getOrCreateRateLimiter("stripe", defaultConfig());

    assertThat(rl).isNotNull();
    assertThat(rl.getName()).isEqualTo("stripe");
  }

  @Test
  @DisplayName("getOrCreateRetry creates a retry for new system")
  void getOrCreate_newSystem_createsRetry() {
    var retry = service.getOrCreateRetry("paypal", defaultConfig());

    assertThat(retry).isNotNull();
    assertThat(retry.getName()).isEqualTo("paypal");
  }

  @Test
  @DisplayName("reload removes old instances so next call creates fresh ones")
  void reload_existingSystem_freshInstanceOnNextGet() {
    var config = defaultConfig();
    var cb1 = service.getOrCreateCircuitBreaker("xendit", config);

    service.reload("xendit");

    var cb2 = service.getOrCreateCircuitBreaker("xendit", config);
    assertThat(cb1).isNotSameAs(cb2);
  }
}
