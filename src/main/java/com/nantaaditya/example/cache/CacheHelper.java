package com.nantaaditya.example.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.nantaaditya.example.model.constant.CacheType;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.properties.embedded.CacheConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;

@Log4j2
public class CacheHelper {

  /** Same as {@link #create(String, CacheConfiguration)}, plus hit/miss/size/eviction meters tagged {@code cache=name}. */
  public static <T> Cache<String, T> create(String name, CacheConfiguration cacheConfiguration,
      MeterRegistry meterRegistry) {
    Cache<String, T> cache = build(name, cacheConfiguration, true);
    CaffeineCacheMetrics.monitor(meterRegistry, cache, name);
    return cache;
  }

  public static <T> Cache<String, T> create(String name, CacheConfiguration cacheConfiguration) {
    return build(name, cacheConfiguration, false);
  }

  private static <T> Cache<String, T> build(String name, CacheConfiguration cacheConfiguration, boolean recordStats) {
    Caffeine<Object, Object> caffeine = Caffeine.newBuilder();
    if (CacheType.SIZE == cacheConfiguration.type()) {
      caffeine = caffeine.maximumSize(cacheConfiguration.size());
    } else if (CacheType.TIME == cacheConfiguration.type()) {
      caffeine = caffeine.expireAfterWrite(cacheConfiguration.timeOut(), cacheConfiguration.timeUnit());
    }

    if (recordStats) {
      caffeine = caffeine.recordStats();
    }

    return caffeine
        .removalListener((RemovalListener<String, T>) (key, value, cause) -> log.log(levelFor(cause), AppLogMessage.message("#Cache {} remove key {} cause {}", name, key, cause)))
        .build();
  }

  // Expiry, explicit eviction and overwrite are the normal life of a cache entry; only losing one to the size
  // cap (or the GC) says the cache is undersized, so only those stand out at WARN.
  static Level levelFor(RemovalCause cause) {
    return switch (cause) {
      case EXPIRED, EXPLICIT, REPLACED -> Level.INFO;
      default -> Level.WARN;
    };
  }
}
