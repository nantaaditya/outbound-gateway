package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.factory.AuthStrategyFactory;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.helper.GatewayHelper;
import com.nantaaditya.example.helper.JsltResponseMapper;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.helper.OutboundMetricsRecorder;
import com.nantaaditya.example.idempotency.IdempotencyGuard;
import com.nantaaditya.example.helper.ParameterInjector;
import com.nantaaditya.example.helper.ParameterResolver;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.dto.ResolvedParameters;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.response.GatewayResponse;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHeaderConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResponseMapperConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.impl.GatewayServiceImpl;
import com.nantaaditya.example.service.impl.GatewayWebClientBuilder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@ExtendWith(MockitoExtension.class)
class GatewayServiceImplTest {

  @Mock
  OutboundSystemRepository systemRepository;
  @Mock
  OutboundHostConfigurationRepository hostConfigRepository;
  @Mock
  OutboundResilienceConfigurationRepository resilienceRepository;
  @Mock
  OutboundHeaderConfigurationRepository headerRepository;
  @Mock
  OutboundAuthConfigurationRepository authRepository;
  @Mock
  OutboundResponseMapperConfigurationRepository mapperRepository;
  @Mock
  OutboundHistoryRepository historyRepository;
  @Mock
  GatewayWebClientBuilder contextBuilder;
  @Mock
  AuthStrategyFactory authStrategyFactory;
  @Mock
  EncryptionHelper encryptionHelper;
  @Mock
  JsltResponseMapper jsltResponseMapper;
  @Mock
  JsonHelper jsonHelper;
  @Mock
  OutboundMetricsRecorder metricsRecorder;
  @Mock
  AuthStrategy authStrategy;
  @Mock
  ParameterResolver parameterResolver;
  @Mock
  IdempotencyGuard idempotencyGuard;

  GatewayServiceImpl gatewayService;

  @BeforeEach
  void setUp() {
    // Not @InjectMocks: GatewayServiceImpl needs a real Gson and a real GatewayHelper
    // (GatewayHelper itself just delegates to jsonHelper/jsltResponseMapper, both already
    // mocked above), rather than Mockito silently injecting null for unmocked constructor args.
    gatewayService = new GatewayServiceImpl(
        systemRepository, hostConfigRepository, resilienceRepository, headerRepository,
        authRepository, mapperRepository, historyRepository, contextBuilder,
        authStrategyFactory, encryptionHelper, jsonHelper, metricsRecorder,
        new Gson(), new GatewayHelper(jsonHelper, jsltResponseMapper),
        parameterResolver, new ParameterInjector(new ObjectMapper()), idempotencyGuard);
    lenient().when(parameterResolver.resolve(any(), any()))
        .thenReturn(Mono.just(ResolvedParameters.empty()));
  }

  @Test
  @DisplayName("evictContext with null hostConfiguration does not throw NPE — cache is still cleared")
  void evictContext_nullHostConfig_doesNotThrowAndCacheIsCleared() {
    OutboundSystemContext contextWithNullHost = OutboundSystemContext.builder()
        .system(OutboundSystem.builder().code("test-code").build())
        .hostConfig(null)
        .build();

    contextCache().put("test-code", contextWithNullHost);

    assertThatCode(() -> gatewayService.evictContext("test-code")).doesNotThrowAnyException();

    verify(contextBuilder, never()).evictWebClient(anyString());
    assertThat(contextCache()).doesNotContainKey("test-code");
  }

  @Test
  @DisplayName("evictContext with non-null hostConfiguration evicts the WebClient pool")
  void evictContext_withHostConfig_evictsWebClientPool() {
    OutboundHostConfiguration hostConfig = OutboundHostConfiguration.builder()
        .baseUrl("https://api.example.com")
        .build();
    OutboundSystemContext context = OutboundSystemContext.builder()
        .system(OutboundSystem.builder().code("live-code").build())
        .hostConfig(hostConfig)
        .build();

    contextCache().put("live-code", context);

    gatewayService.evictContext("live-code");

    verify(contextBuilder).evictWebClient("https://api.example.com");
    assertThat(contextCache()).doesNotContainKey("live-code");
  }

  @Test
  @DisplayName("evictContext for unknown code is a no-op — no NPE, evictWebClient not called")
  void evictContext_unknownCode_isNoOp() {
    assertThatCode(() -> gatewayService.evictContext("nonexistent")).doesNotThrowAnyException();
    verify(contextBuilder, never()).evictWebClient(anyString());
  }

  @Test
  @DisplayName("saveHistory uses historyRepository.saveHistory for all calls — retryCount=0 on first attempt")
  void execute_noIdempotencyKey_historyIsSavedViaRepository() {
    WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient mockWebClient = mock(WebClient.class);
    when(mockWebClient.method(any())).thenReturn(uriSpec);
    when(uriSpec.uri(any(Function.class))).thenReturn(bodySpec);
    when(bodySpec.headers(any())).thenReturn(bodySpec);
    when(bodySpec.exchangeToMono(any())).thenReturn(
        Mono.just(GatewayResponse.passThrough(200, Map.of(), "{}")));

    OutboundSystem system = OutboundSystem.builder()
        .id(1L).code("no-key-gw").httpMethod("POST")
        .hostConfigurationId(1L).apiPath("/path").build();

    contextCache().put("no-key-gw", OutboundSystemContext.builder()
        .system(system)
        .hostConfig(OutboundHostConfiguration.builder().id(1L).baseUrl("http://host").build())
        .resilience(OutboundResilienceConfiguration.bypass())
        .auth(OutboundAuthConfiguration.builder().authType(AuthType.NONE).build())
        .responseMapper(OutboundResponseMapperConfiguration.builder()
            .converterType(ConverterType.NONE).build())
        .headers(List.of())
        .webClient(mockWebClient)
        .build());

    when(jsonHelper.toJsonString(any())).thenReturn("{}");
    when(authStrategyFactory.getStrategy(AuthType.NONE)).thenReturn(authStrategy);
    when(authStrategy.authenticate(any(), any(), any())).thenReturn(
        Mono.just(AuthCredential.empty()));
    when(historyRepository.saveHistory(any(OutboundHistory.class))).thenReturn(
        Mono.just(1));

    gatewayService.execute("no-key-gw", "svc", null, new GatewayRequest(Map.of(), null)).block();

    ArgumentCaptor<OutboundHistory> captor = ArgumentCaptor.forClass(OutboundHistory.class);
    verify(historyRepository).saveHistory(captor.capture());
    assertThat(captor.getValue().getRetryCount()).isZero();
  }

  @Test
  @DisplayName("evictContext also evicts other systems' contexts on the same host, because the pool they hold is recycled")
  void evictContext_alsoEvictsSiblingContextsOnTheSameHost() {
    contextCache().put("a", contextOn("a", "https://shared.example.com"));
    contextCache().put("b", contextOn("b", "https://shared.example.com"));
    contextCache().put("c", contextOn("c", "https://other.example.com"));

    gatewayService.evictContext("a");

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

    gatewayService.evictHost("https://shared.example.com");

    assertThat(contextCache()).containsOnlyKeys("c");
    verify(contextBuilder, times(1)).evictWebClient("https://shared.example.com");
  }

  @Test
  @DisplayName("evictHost for a host without cached contexts still evicts the pool")
  void evictHost_noCachedContexts_stillEvictsPool() {
    gatewayService.evictHost("https://cold.example.com");

    verify(contextBuilder).evictWebClient("https://cold.example.com");
  }

  @Test
  @DisplayName("a context loaded while an eviction happens is not written to the cache (no stale resurrection)")
  void evictContext_duringInFlightLoad_doesNotCacheStaleContext() throws Exception {
    OutboundHostConfiguration host = OutboundHostConfiguration.builder().id(1L).baseUrl("http://host").build();
    OutboundSystem system = OutboundSystem.builder()
        .id(1L).code("race").httpMethod("POST").hostConfigurationId(1L).apiPath("/p").build();
    Sinks.One<OutboundSystem> dbRead = Sinks.one();
    when(systemRepository.findByCodeAndActiveTrue("race")).thenReturn(dbRead.asMono());
    lenient().when(resilienceRepository.findByOutboundSystemId(anyLong()))
        .thenReturn(Mono.just(OutboundResilienceConfiguration.bypass()));
    lenient().when(headerRepository.findByOutboundSystemId(anyLong())).thenReturn(Flux.empty());
    lenient().when(authRepository.findByOutboundSystemId(anyLong())).thenReturn(Mono.empty());
    lenient().when(mapperRepository.findByOutboundSystemIdAndActiveTrue(anyLong())).thenReturn(Mono.empty());
    lenient().when(hostConfigRepository.findById(anyLong())).thenReturn(Mono.just(host));
    lenient().when(contextBuilder.buildContext(any(), any(), any(), any(), any(), any()))
        .thenReturn(contextOn("race", "http://host"));

    // the load is now waiting on the DB read
    CompletableFuture<GatewayResponse> call = gatewayService
        .execute("race", "svc", null, new GatewayRequest(Map.of(), null))
        .onErrorResume(e -> Mono.empty())
        .toFuture();
    gatewayService.evictContext("race");   // admin change lands while the load is in flight
    dbRead.tryEmitValue(system);
    call.get(5, TimeUnit.SECONDS);

    assertThat(contextCache()).doesNotContainKey("race");
  }

  private static OutboundSystemContext contextOn(String code, String baseUrl) {
    return OutboundSystemContext.builder()
        .system(OutboundSystem.builder().code(code).build())
        .hostConfig(OutboundHostConfiguration.builder().baseUrl(baseUrl).build())
        .build();
  }

  @SuppressWarnings("unchecked")
  private ConcurrentMap<String, OutboundSystemContext> contextCache() {
    return (ConcurrentMap<String, OutboundSystemContext>)
        ReflectionTestUtils.getField(gatewayService, "contextCache");
  }
}
