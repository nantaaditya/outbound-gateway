package com.nantaaditya.example.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.entity.OutboundParameter;
import com.nantaaditya.example.model.constant.CacheType;
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.constant.ParameterInjectLocation;
import com.nantaaditya.example.properties.CacheProperties;
import com.nantaaditya.example.properties.embedded.CacheConfiguration;
import com.nantaaditya.example.repository.OutboundParameterRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class OutboundParameterCacheTest {

  private static final long HOST = 7L;

  @Mock
  private OutboundParameterRepository repository;

  private OutboundParameterCache cacheWithTtlSeconds(int seconds) {
    CacheProperties properties = new CacheProperties(Map.of(
        ConstantName.PARAMETERS_CACHE,
        new CacheConfiguration(CacheType.TIME, 0, seconds, TimeUnit.SECONDS)));
    OutboundParameterCache cache = new OutboundParameterCache(repository, properties);
    cache.init();
    return cache;
  }

  private static OutboundParameter parameter(String key) {
    return OutboundParameter.create(HOST, key, new byte[] {1}, ParameterInjectLocation.HEADER, "X-" + key, true);
  }

  @Test
  @DisplayName("second lookup of the same key is served from cache — repository hit once")
  void findActive_secondCall_isServedFromCache() {
    when(repository.findByHostConfigurationIdAndParamKeyInAndActiveTrue(eq(HOST), any()))
        .thenReturn(Flux.just(parameter("A")));
    OutboundParameterCache cache = cacheWithTtlSeconds(60);

    cache.findActive(HOST, List.of("A")).collectList().block();
    List<OutboundParameter> second = cache.findActive(HOST, List.of("A")).collectList().block();

    assertThat(second).extracting(OutboundParameter::getParamKey).containsExactly("A");
    verify(repository, times(1)).findByHostConfigurationIdAndParamKeyInAndActiveTrue(anyLong(), any());
  }

  @Test
  @DisplayName("partial hit — only the missing keys are loaded from the repository")
  void findActive_partialHit_loadsOnlyMissingKeys() {
    when(repository.findByHostConfigurationIdAndParamKeyInAndActiveTrue(eq(HOST), any()))
        .thenReturn(Flux.just(parameter("A")))
        .thenReturn(Flux.just(parameter("B")));
    OutboundParameterCache cache = cacheWithTtlSeconds(60);
    cache.findActive(HOST, List.of("A")).collectList().block();

    List<OutboundParameter> result = cache.findActive(HOST, List.of("A", "B")).collectList().block();

    assertThat(result).extracting(OutboundParameter::getParamKey).containsExactlyInAnyOrder("A", "B");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> loaded = ArgumentCaptor.forClass(Collection.class);
    verify(repository, times(2)).findByHostConfigurationIdAndParamKeyInAndActiveTrue(eq(HOST), loaded.capture());
    assertThat(loaded.getAllValues().get(1)).containsExactly("B");
  }

  @Test
  @DisplayName("misses (unknown / inactive keys) are not cached — a key created later is found immediately")
  void findActive_missingKey_isNotCached() {
    when(repository.findByHostConfigurationIdAndParamKeyInAndActiveTrue(eq(HOST), any()))
        .thenReturn(Flux.empty())
        .thenReturn(Flux.just(parameter("A")));
    OutboundParameterCache cache = cacheWithTtlSeconds(60);

    assertThat(cache.findActive(HOST, List.of("A")).collectList().block()).isEmpty();
    assertThat(cache.findActive(HOST, List.of("A")).collectList().block()).hasSize(1);
  }

  @Test
  @DisplayName("evict forces the next lookup to reload from the repository")
  void evict_nextLookupReloads() {
    when(repository.findByHostConfigurationIdAndParamKeyInAndActiveTrue(eq(HOST), any()))
        .thenReturn(Flux.just(parameter("A")))
        .thenReturn(Flux.just(parameter("A")));
    OutboundParameterCache cache = cacheWithTtlSeconds(60);
    cache.findActive(HOST, List.of("A")).collectList().block();

    cache.evict(HOST, "A");
    cache.findActive(HOST, List.of("A")).collectList().block();

    verify(repository, times(2)).findByHostConfigurationIdAndParamKeyInAndActiveTrue(anyLong(), any());
  }

  @Test
  @DisplayName("same key on a different host is a different cache entry")
  void findActive_differentHost_isNotShared() {
    when(repository.findByHostConfigurationIdAndParamKeyInAndActiveTrue(eq(HOST), any()))
        .thenReturn(Flux.just(parameter("A")));
    when(repository.findByHostConfigurationIdAndParamKeyInAndActiveTrue(eq(HOST + 1), any()))
        .thenReturn(Flux.empty());
    OutboundParameterCache cache = cacheWithTtlSeconds(60);
    cache.findActive(HOST, List.of("A")).collectList().block();

    assertThat(cache.findActive(HOST + 1, List.of("A")).collectList().block()).isEmpty();
  }

  @Test
  @DisplayName("time-out 0 disables caching — every lookup reads the repository")
  void findActive_zeroTtl_alwaysHitsRepository() {
    when(repository.findByHostConfigurationIdAndParamKeyInAndActiveTrue(eq(HOST), any()))
        .thenAnswer(inv -> Flux.just(parameter("A")));
    OutboundParameterCache cache = cacheWithTtlSeconds(0);

    cache.findActive(HOST, List.of("A")).collectList().block();
    cache.findActive(HOST, List.of("A")).collectList().block();

    verify(repository, times(2)).findByHostConfigurationIdAndParamKeyInAndActiveTrue(anyLong(), any());
  }

  @Test
  @DisplayName("all keys cached — repository is not called at all")
  void findActive_allHit_neverCallsRepository() {
    when(repository.findByHostConfigurationIdAndParamKeyInAndActiveTrue(eq(HOST), any()))
        .thenReturn(Flux.just(parameter("A"), parameter("B")));
    OutboundParameterCache cache = cacheWithTtlSeconds(60);
    cache.findActive(HOST, List.of("A", "B")).collectList().block();

    cache.findActive(HOST, List.of("B", "A")).collectList().block();

    verify(repository, times(1)).findByHostConfigurationIdAndParamKeyInAndActiveTrue(anyLong(), any());
    verify(repository, never()).findByHostConfigurationId(anyLong());
  }
}
