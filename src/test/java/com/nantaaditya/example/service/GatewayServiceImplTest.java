package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.factory.AuthStrategyFactory;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.helper.JsltResponseMapper;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.helper.OutboundMetricsRecorder;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
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
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

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

  @InjectMocks
  GatewayServiceImpl gatewayService;

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
  @DisplayName("saveHistory routes to historyRepository.save (not insertHistory) when idempotency key is absent — retryCount=0 on first attempt")
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
        .baseUrl("http://host").apiPath("/path").build();

    contextCache().put("no-key-gw", OutboundSystemContext.builder()
        .system(system)
        .resilience(OutboundResilienceConfiguration.bypass())
        .auth(OutboundAuthConfiguration.builder().authType(AuthType.NONE).build())
        .responseMapper(OutboundResponseMapperConfiguration.builder()
            .converterType(ConverterType.NONE).build())
        .headers(List.of())
        .webClient(mockWebClient)
        .build());

    when(authStrategyFactory.getStrategy(AuthType.NONE)).thenReturn(authStrategy);
    when(authStrategy.authenticate(any(), any(), any())).thenReturn(
        Mono.just(AuthCredential.empty()));
    when(historyRepository.save(any())).thenReturn(
        Mono.just(OutboundHistory.builder().id(1L).build()));

    gatewayService.execute("no-key-gw", "svc", null, new GatewayRequest(Map.of(), null)).block();

    ArgumentCaptor<OutboundHistory> captor = ArgumentCaptor.forClass(OutboundHistory.class);
    verify(historyRepository).save(captor.capture());
    assertThat(captor.getValue().getRetryCount()).isZero();
  }

  @SuppressWarnings("unchecked")
  private ConcurrentMap<String, OutboundSystemContext> contextCache() {
    return (ConcurrentMap<String, OutboundSystemContext>)
        ReflectionTestUtils.getField(gatewayService, "contextCache");
  }
}
