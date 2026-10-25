package com.nantaaditya.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ConverterType;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class SystemContextLoaderTest {

  private static final String HOST_URL = "http://host";

  @Mock private OutboundSystemRepository systemRepository;
  @Mock private OutboundHostConfigurationRepository hostConfigRepository;
  @Mock private OutboundResilienceConfigurationRepository resilienceRepository;
  @Mock private OutboundHeaderConfigurationRepository headerRepository;
  @Mock private OutboundAuthConfigurationRepository authRepository;
  @Mock private OutboundResponseMapperConfigurationRepository mapperRepository;
  @Mock private GatewayWebClientBuilder contextBuilder;

  private SystemContextLoader loader;

  @BeforeEach
  void setUp() {
    loader = new SystemContextLoader(systemRepository, hostConfigRepository, resilienceRepository,
        headerRepository, authRepository, mapperRepository, contextBuilder);
  }

  // --- load ---

  @Test
  @DisplayName("an unknown or inactive code fails with OutboundSystemNotFoundException")
  void load_unknownCode_failsNotFound() {
    when(systemRepository.findByCodeAndActiveTrue("nope")).thenReturn(Mono.empty());

    StepVerifier.create(loader.load("nope"))
        .expectError(OutboundSystemNotFoundException.class)
        .verify();
  }

  @Test
  @DisplayName("the first load reads the database, later loads are served from the cache")
  void load_secondCall_servedFromCache() {
    stubLoadable("a");

    StepVerifier.create(loader.load("a")).expectNextCount(1).verifyComplete();
    StepVerifier.create(loader.load("a")).expectNextCount(1).verifyComplete();

    verify(systemRepository, times(1)).findByCodeAndActiveTrue("a");
    assertThat(contextCache()).containsKey("a");
  }

  @Test
  @DisplayName("overlapping first loads of the same code share a single database read")
  void load_overlapping_shareOneDatabaseRead() throws Exception {
    Sinks.One<OutboundSystem> dbRead = Sinks.one();
    when(systemRepository.findByCodeAndActiveTrue("a")).thenReturn(dbRead.asMono());
    stubChildRows();
    lenient().when(contextBuilder.buildContext(any(), any(), any(), any(), any(), any()))
        .thenReturn(contextOn("a", HOST_URL));

    CompletableFuture<OutboundSystemContext> first = loader.load("a").toFuture();
    CompletableFuture<OutboundSystemContext> second = loader.load("a").toFuture();
    dbRead.tryEmitValue(system("a"));

    assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(second.get(5, TimeUnit.SECONDS));
    verify(systemRepository, times(1)).findByCodeAndActiveTrue("a");
  }

  @Test
  @DisplayName("a system whose host row is missing fails with HostConfigNotFoundException")
  void load_hostMissing_failsNotFound() {
    when(systemRepository.findByCodeAndActiveTrue("a")).thenReturn(Mono.just(system("a")));
    stubChildRows();
    when(hostConfigRepository.findById(anyLong())).thenReturn(Mono.empty());

    StepVerifier.create(loader.load("a"))
        .expectError(HostConfigNotFoundException.class)
        .verify();
    assertThat(contextCache()).doesNotContainKey("a");
  }

  @Test
  @DisplayName("missing optional rows fall back to bypass resilience, NONE authentication and NONE mapper")
  void load_noOptionalRows_usesDefaults() {
    when(systemRepository.findByCodeAndActiveTrue("a")).thenReturn(Mono.just(system("a")));
    when(resilienceRepository.findByOutboundSystemId(anyLong())).thenReturn(Mono.empty());
    when(headerRepository.findByOutboundSystemId(anyLong())).thenReturn(Flux.empty());
    when(authRepository.findByOutboundSystemId(anyLong())).thenReturn(Mono.empty());
    when(mapperRepository.findByOutboundSystemIdAndActiveTrue(anyLong())).thenReturn(Mono.empty());
    when(hostConfigRepository.findById(anyLong())).thenReturn(Mono.just(host(HOST_URL)));
    when(contextBuilder.buildContext(any(), any(), any(), any(), any(), any()))
        .thenReturn(contextOn("a", HOST_URL));

    loader.load("a").block();

    ArgumentCaptor<OutboundResilienceConfiguration> resilience =
        ArgumentCaptor.forClass(OutboundResilienceConfiguration.class);
    ArgumentCaptor<OutboundAuthConfiguration> auth = ArgumentCaptor.forClass(OutboundAuthConfiguration.class);
    ArgumentCaptor<OutboundResponseMapperConfiguration> mapper =
        ArgumentCaptor.forClass(OutboundResponseMapperConfiguration.class);
    verify(contextBuilder).buildContext(any(), any(), resilience.capture(), any(), auth.capture(), mapper.capture());
    assertThat(resilience.getValue().isBypassed()).isTrue();
    assertThat(auth.getValue().getAuthType()).isEqualTo(AuthType.NONE);
    assertThat(mapper.getValue().getConverterType()).isEqualTo(ConverterType.NONE);
  }

  // --- eviction ---

  @Test
  @DisplayName("after evictContext the next load reads the database again")
  void evictContext_nextLoadReloads() {
    stubLoadable("a");
    loader.load("a").block();

    loader.evictContext("a");
    loader.load("a").block();

    verify(systemRepository, times(2)).findByCodeAndActiveTrue("a");
  }

  @Test
  @DisplayName("evictContext with null hostConfiguration does not throw NPE — cache is still cleared")
  void evictContext_nullHostConfig_doesNotThrowAndCacheIsCleared() {
    OutboundSystemContext contextWithNullHost = OutboundSystemContext.builder()
        .system(OutboundSystem.builder().code("test-code").build())
        .hostConfig(null)
        .build();
    contextCache().put("test-code", contextWithNullHost);

    assertThatCode(() -> loader.evictContext("test-code")).doesNotThrowAnyException();

    verify(contextBuilder, never()).evictWebClient(anyString());
    assertThat(contextCache()).doesNotContainKey("test-code");
  }

  @Test
  @DisplayName("evictContext with a hostConfiguration evicts the WebClient pool")
  void evictContext_withHostConfig_evictsWebClientPool() {
    contextCache().put("live-code", contextOn("live-code", "https://api.example.com"));

    loader.evictContext("live-code");

    verify(contextBuilder).evictWebClient("https://api.example.com");
    assertThat(contextCache()).doesNotContainKey("live-code");
  }

  @Test
  @DisplayName("evictContext for an unknown code is a no-op — no NPE, evictWebClient not called")
  void evictContext_unknownCode_isNoOp() {
    assertThatCode(() -> loader.evictContext("nonexistent")).doesNotThrowAnyException();
    verify(contextBuilder, never()).evictWebClient(anyString());
  }

  @Test
  @DisplayName("evictContext also evicts other systems' contexts on the same host, because the pool they hold is recycled")
  void evictContext_alsoEvictsSiblingContextsOnTheSameHost() {
    contextCache().put("a", contextOn("a", "https://shared.example.com"));
    contextCache().put("b", contextOn("b", "https://shared.example.com"));
    contextCache().put("c", contextOn("c", "https://other.example.com"));

    loader.evictContext("a");

    assertThat(contextCache()).containsOnlyKeys("c");
    verify(contextBuilder, times(1)).evictWebClient("https://shared.example.com");
    verify(contextBuilder, never()).evictWebClient("https://other.example.com");
  }

  @Test
  @DisplayName("evictHost drops every cached context of that host and evicts its pool once")
  void evictHost_dropsAllContextsOfTheHostAndEvictsPool() {
    contextCache().put("a", contextOn("a", "https://shared.example.com"));
    contextCache().put("b", contextOn("b", "https://shared.example.com"));
    contextCache().put("c", contextOn("c", "https://other.example.com"));

    loader.evictHost("https://shared.example.com");

    assertThat(contextCache()).containsOnlyKeys("c");
    verify(contextBuilder, times(1)).evictWebClient("https://shared.example.com");
  }

  @Test
  @DisplayName("evictHost for a host without cached contexts still evicts the pool")
  void evictHost_noCachedContexts_stillEvictsPool() {
    loader.evictHost("https://cold.example.com");

    verify(contextBuilder).evictWebClient("https://cold.example.com");
  }

  @Test
  @DisplayName("a context loaded while an eviction happens is not written to the cache (no stale resurrection)")
  void evictContext_duringInFlightLoad_doesNotCacheStaleContext() throws Exception {
    Sinks.One<OutboundSystem> dbRead = Sinks.one();
    when(systemRepository.findByCodeAndActiveTrue("race")).thenReturn(dbRead.asMono());
    stubChildRows();
    lenient().when(contextBuilder.buildContext(any(), any(), any(), any(), any(), any()))
        .thenReturn(contextOn("race", HOST_URL));

    // the load is now waiting on the DB read
    CompletableFuture<OutboundSystemContext> load = loader.load("race").toFuture();
    loader.evictContext("race");   // admin change lands while the load is in flight
    dbRead.tryEmitValue(system("race"));

    assertThat(load.get(5, TimeUnit.SECONDS)).isNotNull();   // the waiting caller still gets its result
    assertThat(contextCache()).doesNotContainKey("race");
  }

  // --- helpers ---

  private void stubLoadable(String code) {
    lenient().when(systemRepository.findByCodeAndActiveTrue(code)).thenReturn(Mono.just(system(code)));
    stubChildRows();
    lenient().when(contextBuilder.buildContext(any(), any(), any(), any(), any(), any()))
        .thenReturn(contextOn(code, HOST_URL));
  }

  private void stubChildRows() {
    lenient().when(resilienceRepository.findByOutboundSystemId(anyLong()))
        .thenReturn(Mono.just(OutboundResilienceConfiguration.bypass()));
    lenient().when(headerRepository.findByOutboundSystemId(anyLong())).thenReturn(Flux.empty());
    lenient().when(authRepository.findByOutboundSystemId(anyLong())).thenReturn(Mono.empty());
    lenient().when(mapperRepository.findByOutboundSystemIdAndActiveTrue(anyLong())).thenReturn(Mono.empty());
    lenient().when(hostConfigRepository.findById(anyLong())).thenReturn(Mono.just(host(HOST_URL)));
  }

  private static OutboundSystem system(String code) {
    return OutboundSystem.builder()
        .id(1L).code(code).httpMethod("POST").hostConfigurationId(1L).apiPath("/p").build();
  }

  private static OutboundHostConfiguration host(String baseUrl) {
    return OutboundHostConfiguration.builder().id(1L).baseUrl(baseUrl).build();
  }

  private static OutboundSystemContext contextOn(String code, String baseUrl) {
    return OutboundSystemContext.builder()
        .system(OutboundSystem.builder().code(code).build())
        .hostConfig(OutboundHostConfiguration.builder().baseUrl(baseUrl).build())
        .headers(List.of())
        .build();
  }

  @SuppressWarnings("unchecked")
  private ConcurrentMap<String, OutboundSystemContext> contextCache() {
    return (ConcurrentMap<String, OutboundSystemContext>) ReflectionTestUtils.getField(loader, "contextCache");
  }
}
