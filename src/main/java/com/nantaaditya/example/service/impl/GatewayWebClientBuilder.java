package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.logger.AppLogMessage;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.net.ssl.SSLException;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.netty.LogbookClientHandler;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;

/**
 * Builds and caches {@link WebClient} instances keyed by {@code baseUrl} (scheme+host+port).
 *
 * <h3>One pool per host</h3>
 * <p>{@code outbound_host_configurations} has a {@code UNIQUE} constraint on {@code base_url},
 * so there is exactly one host config row per host. Multiple outbound systems pointing to
 * the same {@code baseUrl} share both that config row and the pool built from it — no
 * ambiguity, no conflict.
 *
 * <p>The pool is created on the first call to {@link #getOrCreateWebClient} and cached in
 * memory. It is <em>not</em> rebuilt automatically when the DB row changes. Call
 * {@link #evictWebClient(String)} (triggered by {@code POST /api/outbound/{code}/reload})
 * to force re-creation on the next request. Eviction drops all in-flight connections to
 * that host — all systems sharing the same {@code baseUrl} are affected.
 *
 * <h3>Resilience wiring</h3>
 * <p>{@link #buildContext} registers Resilience4j instances only when their enable flag is
 * {@code true}. The application order in {@code GatewayServiceImpl.executeWithResilience}
 * must match the registration order here:
 * <pre>  transformDeferred: RateLimiter → Bulkhead → CircuitBreaker → Retry (outermost)</pre>
 * Retry being outermost means each retry passes through CB and Bulkhead again. Swapping
 * Retry inside CB would bypass the circuit breaker on retries — defeating its purpose.
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class GatewayWebClientBuilder {

  private final WebClient.Builder webClientBuilder;
  private final ResilienceRegistryService resilienceRegistry;
  private final Logbook logbook;
  private final EncryptionHelper encryptionHelper;

  // One WebClient (= one Reactor Netty connection pool) per host (scheme+host+port).
  private final ConcurrentMap<String, WebClient> webClientCache = new ConcurrentHashMap<>();

  public WebClient getOrCreateWebClient(OutboundHostConfiguration hostConfig) {
    return webClientCache.computeIfAbsent(hostConfig.getBaseUrl(), url -> {
      log.info(AppLogMessage.message("#WEBCLIENT - created for {}", url));
      return buildWebClient(hostConfig);
    });
  }

  public void evictWebClient(String baseUrl) {
    webClientCache.remove(baseUrl);
    log.info(AppLogMessage.message("#WEBCLIENT - evicted for {}", baseUrl));
  }

  /**
   * Builds a one-shot {@link WebClient} for test-connection calls.
   *
   * <p><strong>Why not reuse {@link #buildWebClient}?</strong> That method creates a
   * {@link ConnectionProvider#builder(String) pooled} provider sized for production
   * sustained load. Using it here would either:
   * <ul>
   *   <li>Share the production pool — stealing connection slots from live gateway calls, or
   *   <li>Create a new named pool that is never {@code dispose()}d — one leaked pool per
   *       test-connection invocation, accumulating indefinitely until JVM runs out of file
   *       descriptors or native memory.
   * </ul>
   * <p>{@link ConnectionProvider#newConnection()} creates a single-use, non-pooled provider
   * that is released automatically when the connection closes — no explicit dispose needed.
   */
  public WebClient buildDisposableWebClient(OutboundHostConfiguration hostConfig) {
    HttpClient httpClient = HttpClient.create(ConnectionProvider.newConnection())
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, hostConfig.getConnectTimeOutMs());

    if (hostConfig.isDisableSslVerification()) {
      log.warn(AppLogMessage.message(
          "#WEBCLIENT - SSL verification disabled for {} — do not use in production",
          hostConfig.getBaseUrl()));
      httpClient = httpClient.secure(spec -> {
        try {
          spec.sslContext(insecureSsl());
        } catch (SSLException e) {
          throw new IllegalArgumentException("SSL config failed", e);
        }
      });
    }

    return webClientBuilder.clone()
        .baseUrl(hostConfig.getBaseUrl())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
  }

  public OutboundSystemContext buildContext(
      OutboundSystem system,
      OutboundHostConfiguration hostConfig,
      OutboundResilienceConfiguration resilience,
      List<OutboundHeaderConfiguration> headers,
      OutboundAuthConfiguration auth,
      OutboundResponseMapperConfiguration responseMapper) {
    String code = system.getCode();
    OutboundSystemContext.OutboundSystemContextBuilder builder = OutboundSystemContext.builder()
        .system(system)
        .hostConfig(hostConfig)
        .resilience(resilience)
        .headers(headers)
        .auth(auth)
        .responseMapper(responseMapper)
        .webClient(getOrCreateWebClient(hostConfig));

    if (!resilience.isBypassed()) {
      // Each mechanism is registered only when its flag is enabled.
      // Operator application order in executeWithResilience must match:
      //   RateLimiter → Bulkhead → CircuitBreaker → Retry
      // transformDeferred builds inside-out: Retry is the outermost wrapper and
      // re-submits through the full pipeline (including CB + Bulkhead) on each attempt.
      // Wrong order here = Retry bypasses CircuitBreaker, defeating its purpose.
      if (resilience.isCircuitBreakerEnabled())
        builder.circuitBreaker(resilienceRegistry.getOrCreateCircuitBreaker(code, resilience));
      if (resilience.isBulkheadEnabled())
        builder.bulkhead(resilienceRegistry.getOrCreateBulkhead(code, resilience));
      if (resilience.isRateLimiterEnabled())
        builder.rateLimiter(resilienceRegistry.getOrCreateRateLimiter(code, resilience));
      if (resilience.isRetryEnabled())
        builder.retry(resilienceRegistry.getOrCreateRetry(code, resilience));
    }
    return builder.build();
  }

  private WebClient buildWebClient(OutboundHostConfiguration hostConfig) {
    ConnectionProvider connectionProvider = ConnectionProvider.builder(hostConfig.getBaseUrl())
        .maxConnections(hostConfig.getMaxConnections())
        .pendingAcquireMaxCount(hostConfig.getPendingAcquireMaxCount())
        .pendingAcquireTimeout(Duration.ofMillis(hostConfig.getPendingAcquireTimeoutMs()))
        .build();

    HttpClient httpClient = HttpClient.create(connectionProvider)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, hostConfig.getConnectTimeOutMs())
        .doOnConnected(conn -> conn
            .addHandlerLast(new LogbookClientHandler(logbook)));

    if (hostConfig.isDisableSslVerification()) {
      log.warn(AppLogMessage.message("#WEBCLIENT - SSL verification disabled for {} — do not use in production", hostConfig.getBaseUrl()));
      httpClient = httpClient.secure(spec -> {
        try {
          spec.sslContext(insecureSsl());
        } catch (SSLException e) {
          throw new IllegalArgumentException("SSL config failed", e);
        }
      });
    }

    if (hostConfig.isUseProxy() && hostConfig.getProxyHost() != null && hostConfig.getProxyPort() > 0) {
      String proxyHost = hostConfig.getProxyHost();
      int proxyPort = hostConfig.getProxyPort();
      httpClient = httpClient.proxy(proxy -> {
        var spec = proxy.type(ProxyProvider.Proxy.HTTP)
            .host(proxyHost)
            .port(proxyPort);
        if (hostConfig.getProxyUsername() != null && hostConfig.getProxyPassword() != null) {
          spec.username(encryptionHelper.decryptToString(hostConfig.getProxyUsername()))
              .password(p -> encryptionHelper.decryptToString(hostConfig.getProxyPassword()));
        }
      });
    }

    return webClientBuilder.clone()
        .baseUrl(hostConfig.getBaseUrl())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
  }

  private SslContext insecureSsl() throws SSLException {
    return SslContextBuilder.forClient()
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .build();
  }
}
