package com.nantaaditya.example.configuration;

import com.nantaaditya.example.model.logger.AppLogMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bridges the app's {@link MeterRegistry} into micrometer's global registry, and guards
 * against cardinality abuse on gateway metrics.
 *
 * <p>reactor-netty's per-host connection-pool gauges
 * ({@code reactor.netty.connection.provider.*}) register themselves into
 * {@link Metrics#globalRegistry}. The Prometheus registry must be part of that composite
 * for the pool gauges to reach the scrape endpoint.
 *
 * <p>The bridge removes itself from the composite on context close, so test JVMs running
 * many Spring contexts do not accumulate closed registries in the static composite.
 */
@Configuration
@Log4j2
public class ReactorNettyMetricsConfiguration {

  @Bean
  public MeterFilter callerServiceCardinalityGuard() {
    // callerService is header-controlled and unbounded in set size. Without this guard a
    // caller rotating X-Caller-Service values multiplies every api.public series (and its
    // ~20 histogram buckets). MeterFilter.deny() stops registering new values past the cap —
    // existing series keep recording.
    return MeterFilter.maximumAllowableTags("api.public", "callerService", 100, MeterFilter.deny());
  }

  @Bean
  public GlobalRegistryBridge reactorNettyGlobalRegistryBridge(MeterRegistry meterRegistry) {
    return new GlobalRegistryBridge(meterRegistry);
  }

  static class GlobalRegistryBridge implements InitializingBean, DisposableBean {

    private final MeterRegistry meterRegistry;
    private final AtomicBoolean added = new AtomicBoolean(false);

    GlobalRegistryBridge(MeterRegistry meterRegistry) {
      this.meterRegistry = meterRegistry;
    }

    @Override
    public void afterPropertiesSet() {
      if (Metrics.globalRegistry.getRegistries().contains(meterRegistry)) {
        return;
      }
      Metrics.addRegistry(meterRegistry);
      added.set(true);
      log.info(AppLogMessage.message(
          "#METRICS - bridged {} into micrometer global registry for reactor-netty pool metrics",
          meterRegistry.getClass().getSimpleName()));
    }

    @Override
    public void destroy() {
      if (added.get()) {
        Metrics.globalRegistry.remove(meterRegistry);
        added.set(false);
      }
    }
  }
}
