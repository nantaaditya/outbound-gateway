package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.nio.charset.StandardCharsets;
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
 * Builds and caches {@link WebClient} instances keyed by {@code baseUrl} (host-only).
 *
 * <p><b>One pool per host — first-wins caveat (engineers must read this):</b>
 * Multiple outbound systems may share the same {@code baseUrl}. The FIRST system that
 * triggers {@link #getOrCreateWebClient} wins: its {@link OutboundHostConfiguration}
 * values (connect timeout, SSL, proxy, pool size) are used for the shared pool. All
 * subsequent systems pointing to the same host reuse that pool with NO way to override
 * these settings. To change host-level settings, call {@link #evictWebClient(String)} to
 * force re-creation on the next call — this drops ALL in-flight connections for that host.
 *
 * <p>Because of this, {@code outbound_host_configurations} is designed as a host-level
 * table (unique per base_url), not a per-system table.
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class GatewayWebClientBuilder {

  private final WebClient.Builder webClientBuilder;
  private final ResilienceRegistryService resilienceRegistry;
  private final Logbook logbook;

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
          throw new RuntimeException("SSL config failed", e);
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
          spec.username(new String(hostConfig.getProxyUsername(), StandardCharsets.UTF_8))
              .password(p -> new String(hostConfig.getProxyPassword(), StandardCharsets.UTF_8));
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
