package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.nantaaditya.example.gateway.ResilienceDecorator;
import com.nantaaditya.example.gateway.SystemContextLoader;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.helper.GatewayHelper;
import com.nantaaditya.example.helper.JsltResponseMapper;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.helper.OutboundMetricsRecorder;
import com.nantaaditya.example.helper.ParameterInjector;
import com.nantaaditya.example.helper.ParameterResolver;
import com.nantaaditya.example.idempotency.IdempotencyGuard;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.dto.ResolvedParameters;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.response.GatewayResponse;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.service.impl.GatewayServiceImpl;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class GatewayServiceImplTest {

  @Mock
  SystemContextLoader contextLoader;
  @Mock
  OutboundHistoryRepository historyRepository;
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
        contextLoader, historyRepository,
        authStrategyFactory, encryptionHelper, jsonHelper, metricsRecorder,
        new Gson(), new GatewayHelper(jsonHelper, jsltResponseMapper),
        parameterResolver, new ParameterInjector(new ObjectMapper()), idempotencyGuard,
        new ResilienceDecorator());
    lenient().when(parameterResolver.resolve(any(), any()))
        .thenReturn(Mono.just(ResolvedParameters.empty()));
  }

  @Test
  @DisplayName("evictContext is delegated to the context loader")
  void evictContext_delegatesToLoader() {
    gatewayService.evictContext("some-code");

    verify(contextLoader).evictContext("some-code");
  }

  @Test
  @DisplayName("evictHost is delegated to the context loader")
  void evictHost_delegatesToLoader() {
    gatewayService.evictHost("https://api.example.com");

    verify(contextLoader).evictHost("https://api.example.com");
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

    when(contextLoader.load("no-key-gw")).thenReturn(Mono.just(OutboundSystemContext.builder()
        .system(system)
        .hostConfig(OutboundHostConfiguration.builder().id(1L).baseUrl("http://host").build())
        .resilience(OutboundResilienceConfiguration.bypass())
        .auth(OutboundAuthConfiguration.builder().authType(AuthType.NONE).build())
        .responseMapper(OutboundResponseMapperConfiguration.builder()
            .converterType(ConverterType.NONE).build())
        .headers(List.of())
        .webClient(mockWebClient)
        .build()));

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
    verify(idempotencyGuard, never()).guard(anyLong(), anyString(), anyInt(), any());
  }

  @Test
  @DisplayName("a call with an idempotency key goes through the guard with the system's id and TTL")
  void execute_withIdempotencyKey_goesThroughGuard() {
    stubContext("idem-gw", 12);
    when(idempotencyGuard.guard(eq(1L), eq("key-1"), eq(12), any()))
        .thenReturn(Mono.just(GatewayResponse.passThrough(200, Map.of(), "{}")));

    GatewayResponse response = gatewayService
        .execute("idem-gw", "svc", "key-1", new GatewayRequest(Map.of(), null)).block();

    assertThat(response.fromCache()).isFalse();
    verify(idempotencyGuard).guard(eq(1L), eq("key-1"), eq(12), any());
    verify(metricsRecorder, never()).recordIdempotencyHit("idem-gw");
  }

  @Test
  @DisplayName("a result served from a previous execution is counted as an idempotency hit")
  void execute_guardReplays_recordsIdempotencyHit() {
    stubContext("hit-gw", 24);
    when(idempotencyGuard.guard(eq(1L), eq("key-2"), eq(24), any()))
        .thenReturn(Mono.just(GatewayResponse.fromCache(200, Map.of(), "{}", 200, "{}")));

    GatewayResponse response = gatewayService
        .execute("hit-gw", "svc", "key-2", new GatewayRequest(Map.of(), null)).block();

    assertThat(response.fromCache()).isTrue();
    verify(metricsRecorder).recordIdempotencyHit("hit-gw");
  }

  @Test
  @DisplayName("a blank idempotency key is treated as no key")
  void execute_blankIdempotencyKey_bypassesGuard() {
    stubContext("blank-gw", 24);
    // the call proceeds to the HTTP pipeline and fails on the unstubbed web client, which is irrelevant here
    gatewayService.execute("blank-gw", "svc", "  ", new GatewayRequest(Map.of(), null))
        .onErrorResume(e -> Mono.empty()).block();

    verify(idempotencyGuard, never()).guard(anyLong(), anyString(), anyInt(), any());
  }

  private void stubContext(String code, int ttlHours) {
    OutboundSystem system = OutboundSystem.builder()
        .id(1L).code(code).httpMethod("POST").hostConfigurationId(1L).apiPath("/path")
        .idempotencyTtlHours(ttlHours).build();
    when(contextLoader.load(code)).thenReturn(Mono.just(OutboundSystemContext.builder()
        .system(system)
        .hostConfig(OutboundHostConfiguration.builder().id(1L).baseUrl("http://host").build())
        .resilience(OutboundResilienceConfiguration.bypass())
        .auth(OutboundAuthConfiguration.builder().authType(AuthType.NONE).build())
        .responseMapper(OutboundResponseMapperConfiguration.builder()
            .converterType(ConverterType.NONE).build())
        .headers(List.of())
        .build()));
  }
}
