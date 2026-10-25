package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.factory.AuthStrategyFactory;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.exception.HostConfigNotFoundException;
import com.nantaaditya.example.model.exception.OutboundSystemNotFoundException;
import com.nantaaditya.example.model.request.admin.UpdateOutboundSystemRequest;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHeaderConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResponseMapperConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemDetailQuery;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.impl.GatewayWebClientBuilder;
import com.nantaaditya.example.service.impl.OutboundServiceImpl;
import com.nantaaditya.example.service.impl.ResilienceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Updating a system stores the change; whether the running gateway picks it up right away is the caller's choice
 * ({@code reload}). Evicting recycles the cached context, so a batch of edits can store first and reload once.
 */
@ExtendWith(MockitoExtension.class)
class OutboundServiceImplUpdateTest {

  private static final String CODE = "pay";
  private static final long HOST_ID = 7L;

  @Mock private OutboundSystemRepository systemRepository;
  @Mock private OutboundHostConfigurationRepository hostConfigRepository;
  @Mock private GatewayService gatewayService;
  @Mock private OutboundResilienceConfigurationRepository resilienceRepository;
  @Mock private OutboundHeaderConfigurationRepository headerRepository;
  @Mock private OutboundAuthConfigurationRepository authRepository;
  @Mock private OutboundResponseMapperConfigurationRepository mapperRepository;
  @Mock private OutboundSystemDetailQuery systemDetailQuery;
  @Mock private GatewayWebClientBuilder contextBuilder;
  @Mock private AuthStrategyFactory authStrategyFactory;
  @Mock private EncryptionHelper encryptionHelper;
  @Mock private ResilienceRegistryService resilienceRegistryService;
  @Mock private TransactionalOperator transactionalOperator;

  @InjectMocks private OutboundServiceImpl service;

  private final UpdateOutboundSystemRequest request =
      new UpdateOutboundSystemRequest("Pay", "desc", "POST", HOST_ID, "/v2/pay", false, 12);

  @BeforeEach
  void setUp() {
    OutboundSystem existing = OutboundSystem.builder()
        .id(1L).code(CODE).name("Pay").httpMethod("POST").hostConfigurationId(HOST_ID)
        .apiPath("/v1/pay").active(true).idempotencyTtlHours(24).build();
    lenient().when(systemRepository.findByCode(CODE)).thenReturn(Mono.just(existing));
    lenient().when(hostConfigRepository.findById(HOST_ID)).thenReturn(Mono.just(
        OutboundHostConfiguration.builder().id(HOST_ID).baseUrl("https://h.example.com").build()));
    lenient().when(systemRepository.save(any(OutboundSystem.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
  }

  @Test
  @DisplayName("with reload the stored change is applied: the cached context is evicted after the save")
  void update_reloadTrue_evictsContext() {
    StepVerifier.create(service.update(CODE, request, true))
        .assertNext(response -> {
          assertThat(response.apiPath()).isEqualTo("/v2/pay");
          assertThat(response.active()).isFalse();
        })
        .verifyComplete();

    verify(systemRepository).save(any(OutboundSystem.class));
    verify(gatewayService).evictContext(CODE);
  }

  @Test
  @DisplayName("without reload the change is only stored; the running context is left alone")
  void update_reloadFalse_onlyStores() {
    StepVerifier.create(service.update(CODE, request, false)).expectNextCount(1).verifyComplete();

    verify(systemRepository).save(any(OutboundSystem.class));
    verify(gatewayService, never()).evictContext(CODE);
  }

  @Test
  @DisplayName("a failed save evicts nothing")
  void update_saveFails_noEviction() {
    when(systemRepository.save(any(OutboundSystem.class)))
        .thenReturn(Mono.error(new IllegalStateException("db down")));

    StepVerifier.create(service.update(CODE, request, true)).expectError(IllegalStateException.class).verify();

    verify(gatewayService, never()).evictContext(CODE);
  }

  @Test
  @DisplayName("an unknown system is rejected before anything is saved or evicted")
  void update_unknownSystem_rejected() {
    when(systemRepository.findByCode("ghost")).thenReturn(Mono.empty());

    StepVerifier.create(service.update("ghost", request, true))
        .expectError(OutboundSystemNotFoundException.class)
        .verify();

    verify(systemRepository, never()).save(any(OutboundSystem.class));
    verify(gatewayService, never()).evictContext("ghost");
  }

  @Test
  @DisplayName("an unknown host is rejected before anything is saved or evicted")
  void update_unknownHost_rejected() {
    when(hostConfigRepository.findById(HOST_ID)).thenReturn(Mono.empty());

    StepVerifier.create(service.update(CODE, request, true))
        .expectError(HostConfigNotFoundException.class)
        .verify();

    verify(systemRepository, never()).save(any(OutboundSystem.class));
    verify(gatewayService, never()).evictContext(CODE);
  }
}
