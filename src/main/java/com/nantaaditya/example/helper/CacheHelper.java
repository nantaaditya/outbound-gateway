package com.nantaaditya.example.helper;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.nantaaditya.example.model.constant.CacheType;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.properties.embedded.CacheConfiguration;
import lombok.extern.log4j.Log4j2;

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
        .removalListener((RemovalListener<String, T>) (key, value, cause) -> log.warn(AppLogMessage.message("#Cache {} remove key {} cause {}", name, key, cause)))
        .build();
  }
}
