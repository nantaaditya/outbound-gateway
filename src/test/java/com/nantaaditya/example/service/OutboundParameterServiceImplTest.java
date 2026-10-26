package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.cache.OutboundParameterCache;
import com.nantaaditya.example.crypto.EncryptionHelper;
import com.nantaaditya.example.entity.OutboundParameter;
import com.nantaaditya.example.model.constant.ParameterInjectLocation;
import com.nantaaditya.example.model.exception.HostConfigNotFoundException;
import com.nantaaditya.example.model.exception.InvalidParameterException;
import com.nantaaditya.example.model.exception.ParameterNotFoundException;
import com.nantaaditya.example.model.request.admin.UpsertParametersRequest;
import com.nantaaditya.example.model.request.admin.UpsertParametersRequest.Item;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundParameterRepository;
import com.nantaaditya.example.service.impl.OutboundParameterServiceImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class OutboundParameterServiceImplTest {

  private static final long HOST_ID = 7L;
  private static final byte[] ENCRYPTED = {1, 2, 3};

  @Mock
  private OutboundParameterRepository parameterRepository;
  @Mock
  private OutboundHostConfigurationRepository hostConfigRepository;
  @Mock
  private EncryptionHelper encryptionHelper;
  @Mock
  private OutboundParameterCache parameterCache;
  @Mock
  private TransactionalOperator transactionalOperator;

  private OutboundParameterServiceImpl service;
  // flips to true once the Mono handed to the transaction operator has completed (= "committed")
  private final AtomicBoolean committed = new AtomicBoolean(false);

  @BeforeEach
  void setUp() {
    service = new OutboundParameterServiceImpl(parameterRepository, hostConfigRepository,
        encryptionHelper, parameterCache, transactionalOperator);
    lenient().when(hostConfigRepository.existsById(HOST_ID)).thenReturn(Mono.just(true));
    lenient().when(encryptionHelper.encryptString(anyString())).thenReturn(ENCRYPTED);
    lenient().when(parameterRepository.findByHostConfigurationIdAndParamKey(eq(HOST_ID), anyString()))
        .thenReturn(Mono.empty());
    lenient().when(transactionalOperator.transactional(any(Mono.class)))
        .thenAnswer(inv -> ((Mono<?>) inv.getArgument(0)).doOnSuccess(v -> committed.set(true)));
  }

  @Test
  @DisplayName("upsert saves every item inside one transaction")
  void upsert_savesAllItemsInOneTransaction() {
    stubSaveReturnsArgument();

    StepVerifier.create(service.upsert(HOST_ID, request(item("a"), item("b"))))
        .assertNext(result -> assertThat(result).hasSize(2))
        .verifyComplete();

    verify(transactionalOperator).transactional(any(Mono.class));
  }

  @Test
  @DisplayName("upsert evicts cache entries only after the transaction has completed")
  void upsert_evictsAfterCommit() {
    stubSaveReturnsArgument();
    List<Boolean> committedAtEviction = new ArrayList<>();
    doAnswer(inv -> {
      committedAtEviction.add(committed.get());
      return null;
    }).when(parameterCache).evict(anyLong(), anyString());

    service.upsert(HOST_ID, request(item("a"), item("b"))).block();

    verify(parameterCache).evict(HOST_ID, "a");
    verify(parameterCache).evict(HOST_ID, "b");
    assertThat(committedAtEviction).containsExactly(true, true);
  }

  @Test
  @DisplayName("when a later item fails the error propagates and nothing is evicted")
  void upsert_laterItemFails_noEviction() {
    when(parameterRepository.save(any(OutboundParameter.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)))
        .thenReturn(Mono.error(new IllegalStateException("db down")));

    StepVerifier.create(service.upsert(HOST_ID, request(item("a"), item("b"), item("c"))))
        .expectError(IllegalStateException.class)
        .verify();

    // the transaction rolls back, so no cache entry may be invalidated for the aborted request
    verify(parameterCache, never()).evict(anyLong(), anyString());
    assertThat(committed).isFalse();
  }

  @Test
  @DisplayName("unknown host is rejected before anything is written")
  void upsert_unknownHost_rejected() {
    when(hostConfigRepository.existsById(HOST_ID)).thenReturn(Mono.just(false));

    StepVerifier.create(service.upsert(HOST_ID, request(item("a"))))
        .expectError(HostConfigNotFoundException.class)
        .verify();

    verify(parameterRepository, never()).save(any(OutboundParameter.class));
    verify(parameterCache, never()).evict(anyLong(), anyString());
  }

  @Test
  @DisplayName("duplicate keys in one request are rejected before anything is written")
  void upsert_duplicateKeys_rejected() {
    StepVerifier.create(service.upsert(HOST_ID, request(item("a"), item("a"))))
        .expectError(InvalidParameterException.class)
        .verify();

    verify(parameterRepository, never()).save(any(OutboundParameter.class));
    verify(parameterCache, never()).evict(anyLong(), anyString());
  }

  @Test
  @DisplayName("delete removes the parameter and then evicts it from the cache")
  void delete_existingKey_evictsAfterDelete() {
    OutboundParameter existing = OutboundParameter.create(HOST_ID, "a", ENCRYPTED,
        ParameterInjectLocation.HEADER, "X-A", true);
    when(parameterRepository.findByHostConfigurationIdAndParamKey(HOST_ID, "a"))
        .thenReturn(Mono.just(existing));
    when(parameterRepository.delete(existing)).thenReturn(Mono.empty());

    StepVerifier.create(service.delete(HOST_ID, "a"))
        .expectNext(Boolean.TRUE)
        .verifyComplete();

    verify(parameterCache).evict(HOST_ID, "a");
  }

  @Test
  @DisplayName("delete of an unknown key fails with ParameterNotFoundException and evicts nothing")
  void delete_unknownKey_notFound() {
    StepVerifier.create(service.delete(HOST_ID, "missing"))
        .expectError(ParameterNotFoundException.class)
        .verify();

    verify(parameterCache, never()).evict(anyLong(), anyString());
  }

  private void stubSaveReturnsArgument() {
    when(parameterRepository.save(any(OutboundParameter.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
  }

  private static UpsertParametersRequest request(Item... items) {
    return new UpsertParametersRequest(List.of(items));
  }

  private static Item item(String key) {
    return new Item(key, "value-" + key, ParameterInjectLocation.HEADER, "X-" + key, true);
  }
}
