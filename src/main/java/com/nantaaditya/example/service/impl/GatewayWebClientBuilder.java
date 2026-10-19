package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.dto.OutboundSystemContext.OutboundSystemContextBuilder;
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
import reactor.core.publisher.Mono;
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
 * to force re-creation on the next request. The evicted pool is disposed after a grace period,
 * so requests that already hold a connection finish normally while requests still queued for one
 * fail. All systems sharing the same {@code baseUrl} are affected.
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

  // How long an evicted pool stays alive before it is disposed, so requests that already hold a
  // connection from it can finish. Requests still queued for a connection at disposal time fail.
  private static final Duration DEFAULT_DISPOSE_GRACE_PERIOD = Duration.ofSeconds(30);

  // The WebClient and the pool behind it are kept together: the pool can only be disposed if we still
  // hold it when the entry is evicted.
  private record PooledClient(WebClient webClient, ConnectionProvider provider) {}

  // One WebClient (= one Reactor Netty connection pool) per host (scheme+host+port).
  private final ConcurrentMap<String, PooledClient> webClientCache = new ConcurrentHashMap<>();

  public WebClient getOrCreateWebClient(OutboundHostConfiguration hostConfiguration) {
    return webClientCache.computeIfAbsent(
        hostConfiguration.getBaseUrl(),
        url -> {
          log.info(AppLogMessage.message("#WEBCLIENT - created for {}", url));
          return buildWebClient(hostConfiguration);
        }
    ).webClient();
  }

  /**
   * Removes the cached client for {@code baseUrl} and disposes its connection pool after
   * {@link #disposeGracePeriod()}. Never blocks. Callers must also drop every cached context that still
   * references the evicted {@link WebClient} (see {@code GatewayService#evictHost}), otherwise those
   * contexts keep using a pool that is about to be disposed.
   */
  public void evictWebClient(String baseUrl) {
    PooledClient removed = webClientCache.remove(baseUrl);
    log.info(AppLogMessage.message("#WEBCLIENT - evicted for {}", baseUrl));
    if (removed == null) {
      return;
    }
    // defer: disposeLater() must run after the delay, not when the Mono is assembled
    Mono.defer(removed.provider()::disposeLater)
        .delaySubscription(disposeGracePeriod())
        .subscribe(
            unused -> { },
            error -> log.warn(AppLogMessage.message("#WEBCLIENT - failed to dispose pool of {}: {}",
                baseUrl, error.getMessage()).error(error)),
            () -> log.info(AppLogMessage.message("#WEBCLIENT - disposed pool of {}", baseUrl)));
  }

  protected Duration disposeGracePeriod() {
    return DEFAULT_DISPOSE_GRACE_PERIOD;
  }

  protected ConnectionProvider newConnectionProvider(OutboundHostConfiguration hostConfig) {
    return ConnectionProvider.builder(hostConfig.getBaseUrl())
        .maxConnections(hostConfig.getMaxConnections())
        .pendingAcquireMaxCount(hostConfig.getPendingAcquireMaxCount())
        .pendingAcquireTimeout(Duration.ofMillis(hostConfig.getPendingAcquireTimeoutMs()))
        .build();
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

    httpClient = disableSsl(hostConfig, httpClient);
    httpClient = configureProxy(hostConfig, httpClient);

    return webClientBuilder.clone()
        .baseUrl(hostConfig.getBaseUrl())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
  }

  public OutboundSystemContext buildContext(
      OutboundSystem outboundSystem,
      OutboundHostConfiguration hostConfiguration,
      OutboundResilienceConfiguration resilienceConfiguration,
      List<OutboundHeaderConfiguration> headerConfiguration,
      OutboundAuthConfiguration authConfiguration,
      OutboundResponseMapperConfiguration responseMapper) {
    String code = outboundSystem.getCode();
    OutboundSystemContextBuilder builder = buildOutboundSystemContext(outboundSystem,
        hostConfiguration, resilienceConfiguration, headerConfiguration, authConfiguration,
        responseMapper
    );

    if (!resilienceConfiguration.isBypassed()) {
      // Each mechanism is registered only when its flag is enabled.
      // Operator application order in executeWithResilience must match:
      // RateLimiter → Bulkhead → CircuitBreaker → Retry
      // transformDeferred builds inside-out: Retry is the outermost wrapper and
      // re-submits through the full pipeline (including CB + Bulkhead) on each attempt.
      // Wrong order here = Retry bypasses CircuitBreaker, defeating its purpose.
      if (resilienceConfiguration.isCircuitBreakerEnabled())
        builder.circuitBreaker(resilienceRegistry.getOrCreateCircuitBreaker(code, resilienceConfiguration));
      if (resilienceConfiguration.isBulkheadEnabled())
        builder.bulkhead(resilienceRegistry.getOrCreateBulkhead(code, resilienceConfiguration));
      if (resilienceConfiguration.isRateLimiterEnabled())
        builder.rateLimiter(resilienceRegistry.getOrCreateRateLimiter(code, resilienceConfiguration));
      if (resilienceConfiguration.isRetryEnabled())
        builder.retry(resilienceRegistry.getOrCreateRetry(code, resilienceConfiguration));
    }
    return builder.build();
  }

  private OutboundSystemContextBuilder buildOutboundSystemContext(OutboundSystem outboundSystem,
      OutboundHostConfiguration hostConfiguration,
      OutboundResilienceConfiguration resilienceConfiguration,
      List<OutboundHeaderConfiguration> headerConfiguration,
      OutboundAuthConfiguration authConfiguration,
      OutboundResponseMapperConfiguration responseMapper) {
    return OutboundSystemContext.builder()
        .system(outboundSystem)
        .hostConfig(hostConfiguration)
        .resilience(resilienceConfiguration)
        .headers(headerConfiguration)
        .auth(authConfiguration)
        .responseMapper(responseMapper)
        .webClient(getOrCreateWebClient(hostConfiguration));
  }

  private PooledClient buildWebClient(OutboundHostConfiguration hostConfig) {
    ConnectionProvider connectionProvider = newConnectionProvider(hostConfig);

    HttpClient httpClient = HttpClient.create(connectionProvider)
        // metrics(true, uriFn) keeps the DEFAULT recorder — a non-null recorder is what
        // enables the per-pool connection gauges (PooledConnectionProvider.acquire) AND the
        // documented reactor_netty_http_client_* families. The uri tag is bounded to the
        // first path segment so upstream paths cannot multiply series cardinality.
        .metrics(true, GatewayWebClientBuilder::uriTagValue)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, hostConfig.getConnectTimeOutMs())
        .doOnConnected(conn -> conn
            .addHandlerLast(new LogbookClientHandler(logbook))
        );

    httpClient = disableSsl(hostConfig, httpClient);
    httpClient = configureProxy(hostConfig, httpClient);

    WebClient webClient = webClientBuilder.clone()
        .baseUrl(hostConfig.getBaseUrl())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
    return new PooledClient(webClient, connectionProvider);
  }

  private static String uriTagValue(String uri) {
    if (uri == null || uri.isBlank() || !uri.startsWith("/")) {
      return "UNKNOWN";
    }
    int secondSlash = uri.indexOf('/', 1);
    return secondSlash > 0 ? uri.substring(0, secondSlash) : uri;
  }

  private HttpClient disableSsl(OutboundHostConfiguration hostConfig, HttpClient httpClient) {
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
    return httpClient;
  }

  private HttpClient configureProxy(OutboundHostConfiguration hostConfig, HttpClient httpClient) {
    if (hostConfig.isUseProxy() && hostConfig.getProxyHost() != null && hostConfig.getProxyPort() > 0) {
      String proxyHost = hostConfig.getProxyHost();
      int proxyPort = hostConfig.getProxyPort();
      httpClient = httpClient.proxy(proxy -> {
        ProxyProvider.Builder builder = proxy.type(ProxyProvider.Proxy.HTTP)
            .host(proxyHost)
            .port(proxyPort);
        if (hostConfig.getProxyUsername() != null && hostConfig.getProxyPassword() != null) {
          builder.username(encryptionHelper.decryptToString(hostConfig.getProxyUsername()))
              .password(p -> encryptionHelper.decryptToString(hostConfig.getProxyPassword()));
        }
      });
    }
    return httpClient;
  }

  private SslContext insecureSsl() throws SSLException {
    return SslContextBuilder.forClient()
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .build();
  }
}
