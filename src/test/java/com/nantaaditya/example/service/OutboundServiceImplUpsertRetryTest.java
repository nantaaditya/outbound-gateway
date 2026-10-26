package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.crypto.EncryptionHelper;
import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.factory.AuthStrategyFactory;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.request.admin.UpsertHeadersRequest;
import com.nantaaditya.example.model.request.admin.UpsertResponseMapperRequest;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Two first-time upserts for the same system can both pass the "find" and then both try to insert; the
 * unique constraint (V12) rejects the loser. That loser must re-read and update instead of failing.
 */
@ExtendWith(MockitoExtension.class)
class OutboundServiceImplUpsertRetryTest {

  private static final String CODE = "sys-1";
  private static final long SYSTEM_ID = 1L;

  @Mock private OutboundSystemRepository systemRepository;
  @Mock private OutboundResponseMapperConfigurationRepository mapperRepository;
  @Mock private OutboundHeaderConfigurationRepository headerRepository;
  @Mock private GatewayService gatewayService;
  @Mock private TransactionalOperator transactionalOperator;
  @Mock private OutboundResilienceConfigurationRepository resilienceRepository;
  @Mock private OutboundAuthConfigurationRepository authRepository;
  @Mock private OutboundHostConfigurationRepository hostConfigRepository;
  @Mock private OutboundSystemDetailQuery systemDetailQuery;
  @Mock private GatewayWebClientBuilder contextBuilder;
  @Mock private AuthStrategyFactory authStrategyFactory;
  @Mock private EncryptionHelper encryptionHelper;
  @Mock private ResilienceRegistryService resilienceRegistryService;

  @InjectMocks private OutboundServiceImpl service;

  private final UpsertResponseMapperRequest mapperRequest =
      new UpsertResponseMapperRequest(ConverterType.NONE, null, null, true);

  @BeforeEach
  void setUp() {
    OutboundSystem system = OutboundSystem.builder().id(SYSTEM_ID).code(CODE).build();
    lenient().when(systemRepository.findByCode(CODE)).thenReturn(Mono.just(system));
    lenient().when(transactionalOperator.transactional(any(Mono.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  @DisplayName("mapper upsert that loses the insert race re-reads the winner's row and updates it")
  void upsertResponseMapper_duplicateKey_retriesWithFreshRead() {
    OutboundResponseMapperConfiguration winner = OutboundResponseMapperConfiguration.empty(SYSTEM_ID);
    AtomicInteger finds = new AtomicInteger();
    // cold Mono like a real repository: the lookup runs again on every subscription
    when(mapperRepository.findByOutboundSystemId(SYSTEM_ID)).thenAnswer(inv ->
        Mono.defer(() -> finds.getAndIncrement() == 0 ? Mono.empty() : Mono.just(winner)));
    AtomicInteger saves = new AtomicInteger();
    when(mapperRepository.save(any(OutboundResponseMapperConfiguration.class))).thenAnswer(inv ->
        saves.getAndIncrement() == 0
            ? Mono.error(new DuplicateKeyException("duplicate key value violates unique constraint"))
            : Mono.just(inv.getArgument(0)));

    StepVerifier.create(service.upsertResponseMapper(CODE, mapperRequest))
        .expectNext(Boolean.TRUE)
        .verifyComplete();

    assertThat(finds).hasValue(2);
    assertThat(saves).hasValue(2);
    verify(gatewayService, times(1)).evictContext(CODE);
  }

  @Test
  @DisplayName("a persistent conflict is retried a bounded number of times, then reaches the caller unchanged")
  void upsertResponseMapper_persistentConflict_givesUpAfterBoundedRetries() {
    when(mapperRepository.findByOutboundSystemId(SYSTEM_ID)).thenAnswer(inv -> Mono.empty());
    when(mapperRepository.save(any(OutboundResponseMapperConfiguration.class)))
        .thenReturn(Mono.error(new DuplicateKeyException("duplicate")));

    StepVerifier.create(service.upsertResponseMapper(CODE, mapperRequest))
        .expectError(DuplicateKeyException.class)
        .verify();

    // 1 attempt + 3 retries
    verify(mapperRepository, times(4)).save(any(OutboundResponseMapperConfiguration.class));
    verify(gatewayService, never()).evictContext(CODE);
  }

  @Test
  @DisplayName("an optimistic-lock conflict (another upsert updated the row first) re-reads and updates again")
  void upsertResponseMapper_optimisticLockFailure_retriesWithFreshRead() {
    OutboundResponseMapperConfiguration current = OutboundResponseMapperConfiguration.empty(SYSTEM_ID);
    AtomicInteger finds = new AtomicInteger();
    when(mapperRepository.findByOutboundSystemId(SYSTEM_ID)).thenAnswer(inv ->
        Mono.defer(() -> {
          finds.incrementAndGet();
          return Mono.just(current);
        }));
    AtomicInteger saves = new AtomicInteger();
    when(mapperRepository.save(any(OutboundResponseMapperConfiguration.class))).thenAnswer(inv ->
        saves.getAndIncrement() < 2
            ? Mono.error(new OptimisticLockingFailureException("Failed to update versioned entity"))
            : Mono.just(inv.getArgument(0)));

    StepVerifier.create(service.upsertResponseMapper(CODE, mapperRequest))
        .expectNext(Boolean.TRUE)
        .verifyComplete();

    assertThat(finds).hasValue(3);
    assertThat(saves).hasValue(3);
    verify(gatewayService, times(1)).evictContext(CODE);
  }

  @Test
  @DisplayName("errors other than a unique violation are not retried")
  void upsertResponseMapper_otherError_notRetried() {
    when(mapperRepository.findByOutboundSystemId(SYSTEM_ID)).thenAnswer(inv -> Mono.empty());
    when(mapperRepository.save(any(OutboundResponseMapperConfiguration.class)))
        .thenReturn(Mono.error(new IllegalStateException("db down")));

    StepVerifier.create(service.upsertResponseMapper(CODE, mapperRequest))
        .expectError(IllegalStateException.class)
        .verify();

    verify(mapperRepository, times(1)).save(any(OutboundResponseMapperConfiguration.class));
    verify(gatewayService, never()).evictContext(CODE);
  }

  @Test
  @DisplayName("headers upsert that loses the insert race repeats delete + insert once and succeeds")
  void upsertHeaders_duplicateKey_retries() {
    when(headerRepository.deleteByOutboundSystemId(SYSTEM_ID)).thenReturn(Mono.empty());
    AtomicInteger saves = new AtomicInteger();
    when(headerRepository.save(any(OutboundHeaderConfiguration.class))).thenAnswer(inv ->
        saves.getAndIncrement() == 0
            ? Mono.error(new DuplicateKeyException("duplicate"))
            : Mono.just(inv.getArgument(0)));

    StepVerifier.create(service.upsertHeaders(CODE,
            new UpsertHeadersRequest(Map.of("X-A", List.of("1")))))
        .expectNext(Boolean.TRUE)
        .verifyComplete();

    assertThat(saves).hasValue(2);
    verify(headerRepository, times(2)).deleteByOutboundSystemId(SYSTEM_ID);
    verify(gatewayService, times(1)).evictContext(CODE);
  }
}
