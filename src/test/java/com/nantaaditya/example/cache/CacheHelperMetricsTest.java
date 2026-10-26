package com.nantaaditya.example.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Cache;
import com.nantaaditya.example.model.constant.CacheType;
import com.nantaaditya.example.properties.embedded.CacheConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A cache that is sized or timed by configuration must show whether the setting works: hits, misses, size. */
class CacheHelperMetricsTest {

  private static final CacheConfiguration TIMED = new CacheConfiguration(CacheType.TIME, 0, 10, TimeUnit.MINUTES);

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  @DisplayName("hits and misses are counted per cache name")
  void create_withRegistry_recordsHitsAndMisses() {
    Cache<String, String> cache = CacheHelper.create("parameters", TIMED, registry);

    cache.getIfPresent("absent");
    cache.put("k", "v");
    cache.getIfPresent("k");

    assertThat(registry.get("cache.gets").tag("cache", "parameters").tag("result", "hit")
        .functionCounter().count()).isEqualTo(1.0);
    assertThat(registry.get("cache.gets").tag("cache", "parameters").tag("result", "miss")
        .functionCounter().count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("each cache is reported under its own name, and the size gauge follows the content")
  void create_twoCaches_separateSeries() {
    Cache<String, String> first = CacheHelper.create("contexts", TIMED, registry);
    CacheHelper.create("jslt", TIMED, registry);

    first.put("a", "1");
    first.put("b", "2");
    first.cleanUp();

    assertThat(registry.get("cache.size").tag("cache", "contexts").gauge().value()).isEqualTo(2.0);
    assertThat(registry.get("cache.size").tag("cache", "jslt").gauge().value()).isZero();
  }

  @Test
  @DisplayName("without a registry the cache still works and registers nothing")
  void create_withoutRegistry_noMeters() {
    Cache<String, String> cache = CacheHelper.create("plain", TIMED);

    cache.put("k", "v");

    assertThat(cache.getIfPresent("k")).isEqualTo("v");
    assertThat(registry.getMeters()).isEmpty();
  }
}
