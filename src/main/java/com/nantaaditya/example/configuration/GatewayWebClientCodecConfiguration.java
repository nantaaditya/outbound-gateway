package com.nantaaditya.example.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sets how large an upstream response the gateway is willing to hold in memory.
 *
 * <p>Spring's default is 256 KB, which turned perfectly valid larger answers into a "503 internal gateway error"
 * even though the upstream had already processed the request. The limit is now explicit and configurable
 * ({@code apps.gateway.max-response-bytes}, env {@code MAX_RESPONSE_BYTES}, default 10 MB); a response above it is
 * reported as 502 "upstream response too large". It applies to every client built from the auto-configured
 * {@code WebClient.Builder}: the gateway's per-host clients and the OAuth token client. It does not affect request
 * bodies received by the gateway (see {@code apps.filter.max-body-bytes}).
 */
@Configuration
public class GatewayWebClientCodecConfiguration {

  @Bean
  public WebClientCustomizer maxResponseSizeCustomizer(
      @Value("${apps.gateway.max-response-bytes:10485760}") int maxResponseBytes) {
    return builder -> builder.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxResponseBytes));
  }
}
