package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.factory.AuthStrategyFactory;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.helper.JsltResponseMapper;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.helper.OutboundMetricsRecorder;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHeaderConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResponseMapperConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.impl.GatewayServiceImpl;
import com.nantaaditya.example.service.impl.GatewayWebClientBuilder;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GatewayServiceImplTest {

  @Mock OutboundSystemRepository systemRepository;
  @Mock OutboundHostConfigurationRepository hostConfigRepository;
  @Mock OutboundResilienceConfigurationRepository resilienceRepository;
  @Mock OutboundHeaderConfigurationRepository headerRepository;
  @Mock OutboundAuthConfigurationRepository authRepository;
  @Mock OutboundResponseMapperConfigurationRepository mapperRepository;
  @Mock OutboundHistoryRepository historyRepository;
  @Mock GatewayWebClientBuilder contextBuilder;
  @Mock AuthStrategyFactory authStrategyFactory;
  @Mock EncryptionHelper encryptionHelper;
  @Mock JsltResponseMapper jsltResponseMapper;
  @Mock JsonHelper jsonHelper;
  @Mock OutboundMetricsRecorder metricsRecorder;

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

  @SuppressWarnings("unchecked")
  private ConcurrentMap<String, OutboundSystemContext> contextCache() {
    return (ConcurrentMap<String, OutboundSystemContext>)
        ReflectionTestUtils.getField(gatewayService, "contextCache");
  }
}
