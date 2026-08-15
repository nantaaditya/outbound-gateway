package com.nantaaditya.example.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReactorNettyMetricsConfigurationTest {

  private SimpleMeterRegistry registry;

  @AfterEach
  void cleanup() {
    if (registry != null) {
      Metrics.globalRegistry.remove(registry);
      registry.close();
    }
  }

  @Test
  @DisplayName("bridge adds the app registry to micrometer's global composite when absent")
  void bridge_addsRegistryToGlobalComposite_whenNotPresent() throws Exception {
    registry = new SimpleMeterRegistry();

    new ReactorNettyMetricsConfiguration()
        .reactorNettyGlobalRegistryBridge(registry)
        .afterPropertiesSet();

    assertThat(Metrics.globalRegistry.getRegistries()).contains(registry);
  }

  @Test
  @DisplayName("bridge skips silently when the registry is already in the global composite")
  void bridge_skips_whenAlreadyPresent() throws Exception {
    registry = new SimpleMeterRegistry();
    Metrics.globalRegistry.add(registry);

    new ReactorNettyMetricsConfiguration()
        .reactorNettyGlobalRegistryBridge(registry)
        .afterPropertiesSet();

    assertThat(Metrics.globalRegistry.getRegistries()).contains(registry);
  }
}
