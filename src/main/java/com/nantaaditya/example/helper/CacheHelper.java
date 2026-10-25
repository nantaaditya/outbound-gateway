package com.nantaaditya.example.helper;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.nantaaditya.example.model.constant.CacheType;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.properties.embedded.CacheConfiguration;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;

@Log4j2
public class CacheHelper {

  public static <T> Cache<String, T> create(String name, CacheConfiguration cacheConfiguration) {
    Caffeine<Object, Object> caffeine = Caffeine.newBuilder();
    if (CacheType.SIZE == cacheConfiguration.type()) {
      caffeine = caffeine.maximumSize(cacheConfiguration.size());
    } else if (CacheType.TIME == cacheConfiguration.type()) {
      caffeine = caffeine.expireAfterWrite(cacheConfiguration.timeOut(), cacheConfiguration.timeUnit());
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
