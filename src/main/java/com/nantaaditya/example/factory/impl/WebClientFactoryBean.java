package com.nantaaditya.example.factory.impl;

import com.nantaaditya.example.factory.WebClientFactory;
import com.nantaaditya.example.properties.ClientProperties;
import com.nantaaditya.example.properties.embedded.ClientConfiguration;
import com.nantaaditya.example.properties.embedded.ClientProxyConfiguration;
import com.nantaaditya.example.properties.embedded.ClientTimeOutConfiguration;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.net.ssl.SSLException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.netty.LogbookClientHandler;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;
import reactor.netty.transport.ProxyProvider.Proxy;

public class WebClientFactoryBean implements FactoryBean<WebClientFactory> {

  private final ClientProperties clientProperties;
  private final Logbook logbook;

  public WebClientFactoryBean(
      ClientProperties clientProperties,
      Logbook logbook
  ) {
    this.clientProperties = clientProperties;
    this.logbook = logbook;
  }

  @Override
  public Class<?> getObjectType() {
    return WebClientFactory.class;
  }

  @Override
  public WebClientFactory getObject() throws Exception {
    if (clientProperties.configurations() == null) {
      return new WebClientFactoryImpl(Collections.emptyMap());
    }

    Map<String, WebClient> webClients = clientProperties.configurations()
        .entrySet()
        .stream()
        .collect(Collectors.toMap(entry -> entry.getKey(), entry -> createWebClient(entry.getValue())));
    return new WebClientFactoryImpl(webClients);
  }

  private WebClient createWebClient(ClientConfiguration configuration) {
    WebClient.Builder webClientBuilder = WebClient.builder()
        .baseUrl(configuration.url())
        .defaultHeaders(consumer -> consumer.putAll(configuration.defaultHeaders()));

    HttpClient httpClient = HttpClient.create();
    // ssl configuration
    if (configuration.sslVerificationDisabled()) {
      httpClient = httpClient.secure(context -> {
        try {
          context.sslContext(createSsl());
        } catch (SSLException e) {
          throw new RuntimeException("web client ssl verification failed", e);
        }
      });
    }

    // proxy configuration
    if (configuration.isUseProxy()) {
      httpClient = httpClient.proxy(proxy -> createProxy(configuration.proxy()));
    }

    // time out configuration
    ClientTimeOutConfiguration timeOutConfiguration = configuration.timeOut();
    httpClient = httpClient.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeOutConfiguration.connectTimeOut());
    httpClient = httpClient.doOnConnected(connection -> {
      Connection result = connection
        .addHandlerFirst(new ReadTimeoutHandler(timeOutConfiguration.readTimeOut(), TimeUnit.MILLISECONDS))
        .addHandlerFirst(new WriteTimeoutHandler(timeOutConfiguration.writeTimeOut(), TimeUnit.MILLISECONDS));

        if (configuration.logEnabled()) {
          result.addHandlerLast(new LogbookClientHandler(logbook));
        }
      }
    );
    httpClient = httpClient.responseTimeout(Duration.of(timeOutConfiguration.responseTimeOut(), ChronoUnit.MILLIS));

    return webClientBuilder
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
  }

  private SslContext createSsl() throws SSLException {
    return SslContextBuilder.forClient()
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .build();
  }

  private ProxyProvider createProxy(ClientProxyConfiguration configuration) {
    ProxyProvider.Builder builder = ProxyProvider.builder()
        .type(Proxy.HTTP)
        .host(configuration.host())
        .port(configuration.port());

    if (configuration.isUsingCredentials()) {
      builder
          .username(configuration.username())
          .password(p -> configuration.password());
    }
    return builder.build();
  }

}
