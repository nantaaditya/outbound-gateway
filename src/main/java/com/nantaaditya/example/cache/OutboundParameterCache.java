package com.nantaaditya.example.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.nantaaditya.example.entity.OutboundParameter;
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.properties.CacheProperties;
import com.nantaaditya.example.repository.OutboundParameterRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Read-through cache of <em>active</em> {@link OutboundParameter}s, keyed by {@code hostId:paramKey}.
 *
 * <p>Loaded lazily per key on first use (nothing is preloaded). Entries keep the value
 * <b>encrypted</b> — callers decrypt per call, so plaintext secrets never sit in the cache.
 * Misses (unknown / inactive keys) are not cached, so a newly created key works immediately.
 *
 * <p>Consistency: admin changes evict the entry on this instance right after the DB write. Other
 * instances (and an in-flight load racing an eviction) can serve the old value for at most the TTL
 * configured in {@code apps.cache.configurations.parameters}. A time-out of {@code 0} disables caching.
 */
@RequiredArgsConstructor
@Component
public class OutboundParameterCache {

  private final OutboundParameterRepository parameterRepository;
  private final CacheProperties cacheProperties;
  private final MeterRegistry meterRegistry;

  private Cache<String, OutboundParameter> cache;

  @PostConstruct
  void init() {
    this.cache = CacheHelper.create(ConstantName.PARAMETERS_CACHE,
        cacheProperties.get(ConstantName.PARAMETERS_CACHE), meterRegistry);
  }

  public Flux<OutboundParameter> findActive(long hostId, Collection<String> keys) {
    List<OutboundParameter> hits = new ArrayList<>();
    List<String> misses = new ArrayList<>();
    for (String key : keys) {
      OutboundParameter cached = cache.getIfPresent(cacheKey(hostId, key));
      if (cached == null) {
        misses.add(key);
      } else {
        hits.add(cached);
      }
    }
    if (misses.isEmpty()) {
      return Flux.fromIterable(hits);
    }
    Flux<OutboundParameter> loaded = parameterRepository
        .findByHostConfigurationIdAndParamKeyInAndActiveTrue(hostId, misses)
        .doOnNext(p -> cache.put(cacheKey(hostId, p.getParamKey()), p));
    return Flux.concat(Flux.fromIterable(hits), loaded);
  }

  public void evict(long hostId, String key) {
    cache.invalidate(cacheKey(hostId, key));
  }

  private static String cacheKey(long hostId, String key) {
    return hostId + ":" + key;
  }
}
