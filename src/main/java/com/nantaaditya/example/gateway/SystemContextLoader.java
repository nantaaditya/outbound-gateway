package com.nantaaditya.example.gateway;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.exception.HostConfigNotFoundException;
import com.nantaaditya.example.model.exception.OutboundSystemNotFoundException;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHeaderConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResponseMapperConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.impl.GatewayWebClientBuilder;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Resolves the {@link OutboundSystemContext} for a system code: a hot in-memory cache in front of a database
 * load that is shared between callers, plus the eviction rules that keep that cache consistent with admin
 * changes.
 *
 * <p>The cache has no TTL; entries live until evicted. Eviction is local to this instance.
 */
@Component
@RequiredArgsConstructor
public class SystemContextLoader {

  private final OutboundSystemRepository systemRepository;
  private final OutboundHostConfigurationRepository hostConfigRepository;
  private final OutboundResilienceConfigurationRepository resilienceRepository;
  private final OutboundHeaderConfigurationRepository headerRepository;
  private final OutboundAuthConfigurationRepository authRepository;
  private final OutboundResponseMapperConfigurationRepository mapperRepository;
  private final GatewayWebClientBuilder contextBuilder;

  private final ConcurrentMap<String, OutboundSystemContext> contextCache = new ConcurrentHashMap<>();

  // Bumped by every eviction. A load only populates contextCache if no eviction happened since it started,
  // so a slow load can never resurrect a context that an admin change has just invalidated.
  private final AtomicLong evictionEpoch = new AtomicLong();

  // Thundering-herd protection: computeIfAbsent + Mono.cache() ensures only ONE DB load
  // fires per code even when many requests arrive concurrently before the first load completes.
  // doFinally removes the entry so the next miss after eviction triggers a fresh load.
  private final ConcurrentMap<String, Mono<OutboundSystemContext>> contextLoadCache = new ConcurrentHashMap<>();

  public Mono<OutboundSystemContext> load(String code) {
    OutboundSystemContext cached = contextCache.get(code);
    if (cached != null) {
      return Mono.just(cached);
    }
    return contextLoadCache.computeIfAbsent(code, k ->
        loadFromDb(k)
            .cache()
            .doFinally(signal -> contextLoadCache.remove(k))
    );
  }

  /**
   * Evicts the cached context of {@code code}; when it was cached, its host is evicted too (see
   * {@link #evictHost}). MUST NOT perform any I/O.
   */
  public void evictContext(String code) {
    evictionEpoch.incrementAndGet();
    OutboundSystemContext evicted = contextCache.remove(code);
    contextLoadCache.remove(code);
    if (evicted != null && evicted.getHostConfig() != null) {
      evictHost(evicted.getHostConfig().getBaseUrl());
    }
  }

  /** Evicts every cached context of the host and recycles the host's pool. MUST NOT perform any I/O. */
  public void evictHost(String baseUrl) {
    evictionEpoch.incrementAndGet();
    // Contexts keep a reference to the pooled WebClient; once the pool is recycled (and later disposed)
    // every context of the host must be rebuilt, not only the one that triggered the eviction.
    contextCache.values().removeIf(ctx ->
        ctx.getHostConfig() != null && Objects.equals(baseUrl, ctx.getHostConfig().getBaseUrl()));
    // in-flight loads may have read the old host row; do not let new callers join them
    contextLoadCache.clear();
    contextBuilder.evictWebClient(baseUrl);
  }

  private Mono<OutboundSystemContext> loadFromDb(String code) {
    // Captured when the load starts: any eviction after this point means what we read may already be
    // outdated, so the result is returned to the waiting callers but must not be written to the cache.
    return Mono.defer(() -> loadFromDb(code, evictionEpoch.get()));
  }

  private Mono<OutboundSystemContext> loadFromDb(String code, long epochAtStart) {
    return systemRepository.findByCodeAndActiveTrue(code)
        .flatMap(system -> {
          long systemId = system.getId();
          return Mono.zip(
                  // No resilience row → bypass() synthetic sentinel; all operators skipped.
                  // A row with all flags=false → readTimeOutMs only, no CB/BH/RL/Retry.
                  resilienceRepository.findByOutboundSystemId(systemId)
                      .defaultIfEmpty(OutboundResilienceConfiguration.bypass()),
                  headerRepository.findByOutboundSystemId(systemId).collectList(),
                  // No authentication row → treat as NONE so execution proceeds without authentication headers
                  authRepository.findByOutboundSystemId(systemId)
                      .defaultIfEmpty(OutboundAuthConfiguration.empty(systemId)),
                  mapperRepository.findByOutboundSystemIdAndActiveTrue(systemId)
                      .defaultIfEmpty(OutboundResponseMapperConfiguration.empty(systemId)),
                  hostConfigRepository.findById(system.getHostConfigurationId())
                      .switchIfEmpty(Mono.error(new HostConfigNotFoundException(system.getHostConfigurationId())))
              )
              .map(t -> contextBuilder.buildContext(
                  system,
                  t.getT5(),
                  t.getT1(),
                  t.getT2(),
                  t.getT3(),
                  t.getT4()
                  )
              );
        })
        .doOnNext(context -> {
          if (evictionEpoch.get() == epochAtStart) {
            contextCache.put(code, context);
          }
        })
        .switchIfEmpty(Mono.error(new OutboundSystemNotFoundException(code)));
  }
}
