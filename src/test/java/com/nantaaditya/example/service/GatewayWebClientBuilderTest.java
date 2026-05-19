package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.service.impl.GatewayWebClientBuilder;
import com.nantaaditya.example.service.impl.ResilienceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;

class GatewayWebClientBuilderTest {

  private GatewayWebClientBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new GatewayWebClientBuilder(
        WebClient.builder(),
        new ResilienceRegistryService(),
        Logbook.builder().build()
    );
  }

  private OutboundSystem system() {
    return OutboundSystem.builder()
        .code("xendit")
        .name("Xendit")
        .baseUrl("https://api.xendit.co")
        .apiPath("/v1/charges")
        .httpMethod("POST")
        .active(true)
        .idempotencyTtlHours(24)
        .build();
  }

  private OutboundHostConfiguration hostConfig(boolean disableSsl, boolean useProxy) {
    return OutboundHostConfiguration.builder()
        .baseUrl("https://api.xendit.co")
        .connectTimeOutMs(5000)
        .maxConnections(50)
        .pendingAcquireMaxCount(50)
        .pendingAcquireTimeoutMs(5000)
        .disableSslVerification(disableSsl)
        .useProxy(useProxy)
        .build();
  }

  private OutboundResilienceConfiguration resilience() {
    return OutboundResilienceConfiguration.builder()
        .readTimeOutMs(30000)
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
        .circuitBreakerEnabled(true)
        .bulkheadEnabled(true)
        .rateLimiterEnabled(true)
        .retryEnabled(true)
        .build();
  }

  @Test
  @DisplayName("getOrCreateWebClient returns non-null WebClient for standard host config")
  void build_standardHost_returnsWebClient() {
    var webClient = builder.getOrCreateWebClient(hostConfig(false, false));

    assertThat(webClient).isNotNull();
  }

  @Test
  @DisplayName("getOrCreateWebClient returns WebClient when SSL verification is disabled")
  void build_sslDisabled_returnsWebClient() {
    var webClient = builder.getOrCreateWebClient(hostConfig(true, false));

    assertThat(webClient).isNotNull();
  }

  @Test
  @DisplayName("buildContext creates full context with all resilience components")
  void buildContext_fullConfig_returnsContext() {
    var sys = system();
    var host = hostConfig(false, false);
    var res = resilience();

    var context = builder.buildContext(sys, host, res, java.util.List.of(), null, null);

    assertThat(context.getWebClient()).isNotNull();
    assertThat(context.getCircuitBreaker()).isNotNull();
    assertThat(context.getBulkhead()).isNotNull();
    assertThat(context.getRateLimiter()).isNotNull();
    assertThat(context.getRetry()).isNotNull();
    assertThat(context.getSystem()).isSameAs(sys);
    assertThat(context.getHostConfig()).isSameAs(host);
  }
}
