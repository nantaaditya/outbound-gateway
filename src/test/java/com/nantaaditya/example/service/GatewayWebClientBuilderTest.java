package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.service.impl.GatewayWebClientBuilder;
import com.nantaaditya.example.service.impl.ResilienceRegistryService;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import reactor.netty.resources.ConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;

class GatewayWebClientBuilderTest {

  /** Hands out a spied pool and a configurable dispose grace period so disposal can be observed. */
  private static class ObservableBuilder extends GatewayWebClientBuilder {
    final ConnectionProvider provider = spy(ConnectionProvider.builder("test-pool").maxConnections(1).build());
    private final Duration grace;

    ObservableBuilder(Duration grace) {
      super(WebClient.builder(),
          new ResilienceRegistryService(new SimpleMeterRegistry()),
          Logbook.builder().build(),
          new EncryptionHelper("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
      this.grace = grace;
    }

    @Override
    protected ConnectionProvider newConnectionProvider(OutboundHostConfiguration hostConfig) {
      return provider;
    }

    @Override
    protected Duration disposeGracePeriod() {
      return grace;
    }
  }

  @Test
  @DisplayName("evictWebClient disposes the connection pool of the evicted client (no leaked pool per reload)")
  void evictWebClient_disposesThePool() {
    ObservableBuilder observable = new ObservableBuilder(Duration.ZERO);
    observable.getOrCreateWebClient(hostConfig(false, false));

    observable.evictWebClient("https://api.xendit.co");

    verify(observable.provider, timeout(3000)).disposeLater();
  }

  @Test
  @DisplayName("disposal waits for the grace period so requests already using the pool can finish")
  void evictWebClient_disposalIsDelayedByGracePeriod() {
    ObservableBuilder observable = new ObservableBuilder(Duration.ofMillis(600));
    observable.getOrCreateWebClient(hostConfig(false, false));

    observable.evictWebClient("https://api.xendit.co");

    verify(observable.provider, after(200).never()).disposeLater();
    verify(observable.provider, timeout(5000)).disposeLater();
  }

  @Test
  @DisplayName("evictWebClient for an unknown url disposes nothing")
  void evictWebClient_unknownUrl_disposesNothing() {
    ObservableBuilder observable = new ObservableBuilder(Duration.ZERO);
    observable.getOrCreateWebClient(hostConfig(false, false));

    observable.evictWebClient("https://never-created.example.com");

    verify(observable.provider, after(300).never()).disposeLater();
  }

  @Test
  @DisplayName("evicting the same url twice disposes the pool only once")
  void evictWebClient_twice_disposesOnce() {
    ObservableBuilder observable = new ObservableBuilder(Duration.ZERO);
    observable.getOrCreateWebClient(hostConfig(false, false));

    observable.evictWebClient("https://api.xendit.co");
    observable.evictWebClient("https://api.xendit.co");

    verify(observable.provider, timeout(3000).times(1)).disposeLater();
    verify(observable.provider, after(300).times(1)).disposeLater();
  }

  private GatewayWebClientBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new GatewayWebClientBuilder(
        WebClient.builder(),
        new ResilienceRegistryService(new SimpleMeterRegistry()),
        Logbook.builder().build(),
        new EncryptionHelper("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    );
  }

  private OutboundSystem system() {
    return OutboundSystem.builder()
        .code("xendit")
        .name("Xendit")
        .hostConfigurationId(1L)
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
  @DisplayName("buildDisposableWebClient returns a new WebClient not backed by the shared pool")
  void buildDisposableWebClient_returnsNewInstanceNotFromCache() {
    var host = hostConfig(false, false);
    var pooled = builder.getOrCreateWebClient(host);
    var disposable = builder.buildDisposableWebClient(host);

    assertThat(disposable).isNotNull().isNotSameAs(pooled);
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

  @Test
  @DisplayName("evictWebClient removes the cached instance so next call creates a new one")
  void evictWebClient_removesCache_nextCallCreatesNew() {
    var host = hostConfig(false, false);
    var first = builder.getOrCreateWebClient(host);

    builder.evictWebClient(host.getBaseUrl());

    var second = builder.getOrCreateWebClient(host);
    assertThat(second).isNotNull().isNotSameAs(first);
  }

  @Test
  @DisplayName("getOrCreateWebClient returns the same cached instance on repeated calls")
  void getOrCreateWebClient_repeatCall_returnsSameInstance() {
    var host = hostConfig(false, false);

    var first = builder.getOrCreateWebClient(host);
    var second = builder.getOrCreateWebClient(host);

    assertThat(first).isSameAs(second);
  }

  @Test
  @DisplayName("buildContext with all resilience disabled returns context without resilience components")
  void buildContext_resilienceDisabled_returnsContextWithoutResilience() {
    var sys = system();
    var host = hostConfig(false, false);
    var res = resilienceDisabled();

    var context = builder.buildContext(sys, host, res, java.util.List.of(), null, null);

    assertThat(context.getWebClient()).isNotNull();
    assertThat(context.getCircuitBreaker()).isNull();
    assertThat(context.getBulkhead()).isNull();
    assertThat(context.getRateLimiter()).isNull();
    assertThat(context.getRetry()).isNull();
  }

  private OutboundResilienceConfiguration resilienceDisabled() {
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
        .circuitBreakerEnabled(false)
        .bulkheadEnabled(false)
        .rateLimiterEnabled(false)
        .retryEnabled(false)
        .build();
  }
}
