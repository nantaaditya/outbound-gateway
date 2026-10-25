package com.nantaaditya.example.configuration;

import com.nantaaditya.example.gateway.SystemContextLoader;
import com.nantaaditya.example.service.impl.GatewayWebClientBuilder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Entry counts of the two unbounded caches that are plain maps rather than Caffeine caches (so they get no
 * {@code cache.*} meters): one entry per outbound system and one per outbound host. No tags, so no cardinality.
 */
@Component
@RequiredArgsConstructor
public class GatewayCacheMetrics implements MeterBinder {

  private final SystemContextLoader contextLoader;
  private final GatewayWebClientBuilder webClientBuilder;

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("gateway.context.cache.entries", contextLoader, SystemContextLoader::cachedContextCount)
        .description("Outbound system contexts currently cached")
        .register(registry);
    Gauge.builder("gateway.webclient.cache.entries", webClientBuilder, GatewayWebClientBuilder::cachedClientCount)
        .description("Per-host WebClients (connection pools) currently held")
        .register(registry);
  }
}
