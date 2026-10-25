package com.nantaaditya.example.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.gateway.SystemContextLoader;
import com.nantaaditya.example.service.impl.GatewayWebClientBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The context cache and the per-host WebClient/pool cache have no size bound: their entry counts are the only way
 * to see growth (one entry per system / per host) before it matters.
 */
class GatewayCacheMetricsTest {

  @Test
  @DisplayName("entry gauges follow the live size of the context cache and the WebClient cache")
  void bindTo_registersGaugesReadingLiveSizes() {
    SystemContextLoader loader = mock(SystemContextLoader.class);
    GatewayWebClientBuilder builder = mock(GatewayWebClientBuilder.class);
    when(loader.cachedContextCount()).thenReturn(3, 5);
    when(builder.cachedClientCount()).thenReturn(2);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new GatewayCacheMetrics(loader, builder).bindTo(registry);

    assertThat(registry.get("gateway.context.cache.entries").gauge().value()).isEqualTo(3.0);
    assertThat(registry.get("gateway.context.cache.entries").gauge().value()).isEqualTo(5.0);   // re-read each scrape
    assertThat(registry.get("gateway.webclient.cache.entries").gauge().value()).isEqualTo(2.0);
  }
}
