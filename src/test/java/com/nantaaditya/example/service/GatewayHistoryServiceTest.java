package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.model.exception.OutboundSystemNotFoundException;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.impl.GatewayHistoryServiceImpl;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GatewayHistoryServiceTest {

  @Mock
  private OutboundHistoryRepository historyRepository;

  @Mock
  private OutboundSystemRepository systemRepository;

  @Mock
  private JsonHelper jsonHelper;

  @InjectMocks
  private GatewayHistoryServiceImpl service;

  // --- findHistories ---

  @Test
  @DisplayName("findHistories with valid code resolves systemId and queries DB")
  void findHistories_validCode_queriesWithSystemId() {
    when(systemRepository.findByCode("xendit")).thenReturn(Mono.just(system(42L, "xendit")));
    when(historyRepository.countByFilters(eq(42L), eq(0), isNull(), isNull())).thenReturn(Mono.just(1L));
    when(historyRepository.findPageByFilters(eq(42L), eq(0), isNull(), isNull(), eq(20), eq(0L)))
        .thenReturn(Flux.just(history(42L, "key-1", 200)));

    StepVerifier.create(service.findHistories("xendit", null, null, null, 0, 20))
        .assertNext(page -> {
          assertThat(page.totalElements()).isEqualTo(1L);
          assertThat(page.content()).hasSize(1);
          assertThat(page.content().get(0).outboundSystemId()).isEqualTo(42L);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findHistories with null code uses sentinel 0 and skips system lookup")
  void findHistories_nullCode_usesSentinelZero() {
    when(historyRepository.countByFilters(eq(0L), eq(0), isNull(), isNull())).thenReturn(Mono.just(1L));
    when(historyRepository.findPageByFilters(eq(0L), eq(0), isNull(), isNull(), eq(20), eq(0L)))
        .thenReturn(Flux.just(history(1L, "key-1", 200)));

    StepVerifier.create(service.findHistories(null, null, null, null, 0, 20))
        .assertNext(page -> assertThat(page.totalElements()).isEqualTo(1L))
        .verifyComplete();

    verify(systemRepository, never()).findByCode(any());
  }

  @Test
  @DisplayName("findHistories with blank code uses sentinel 0")
  void findHistories_blankCode_usesSentinelZero() {
    when(historyRepository.countByFilters(eq(0L), eq(0), isNull(), isNull())).thenReturn(Mono.just(0L));
    when(historyRepository.findPageByFilters(eq(0L), eq(0), isNull(), isNull(), eq(20), eq(0L)))
        .thenReturn(Flux.empty());

    StepVerifier.create(service.findHistories("  ", null, null, null, 0, 20))
        .assertNext(page -> assertThat(page.totalElements()).isEqualTo(0L))
        .verifyComplete();
  }

  @Test
  @DisplayName("findHistories with unknown code returns OutboundSystemNotFoundException")
  void findHistories_unknownCode_returnsError() {
    when(systemRepository.findByCode("unknown")).thenReturn(Mono.empty());

    StepVerifier.create(service.findHistories("unknown", null, null, null, 0, 20))
        .expectErrorMatches(e -> e instanceof OutboundSystemNotFoundException)
        .verify();
  }

  @Test
  @DisplayName("findHistories passes status filter to repository")
  void findHistories_withStatus_passesStatusToRepo() {
    when(systemRepository.findByCode("xendit")).thenReturn(Mono.just(system(1L, "xendit")));
    when(historyRepository.countByFilters(eq(1L), eq(500), isNull(), isNull())).thenReturn(Mono.just(0L));
    when(historyRepository.findPageByFilters(eq(1L), eq(500), isNull(), isNull(), anyInt(), anyLong()))
        .thenReturn(Flux.empty());

    StepVerifier.create(service.findHistories("xendit", null, null, 500, 0, 20))
        .assertNext(page -> assertThat(page.totalElements()).isEqualTo(0L))
        .verifyComplete();
  }

  @Test
  @DisplayName("findHistories clamps size to max 100")
  void findHistories_oversizedPage_clampsTo100() {
    when(historyRepository.countByFilters(eq(0L), eq(0), isNull(), isNull())).thenReturn(Mono.just(0L));
    when(historyRepository.findPageByFilters(eq(0L), eq(0), isNull(), isNull(), eq(100), eq(0L)))
        .thenReturn(Flux.empty());

    StepVerifier.create(service.findHistories(null, null, null, null, 0, 999))
        .assertNext(page -> assertThat(page.size()).isEqualTo(100))
        .verifyComplete();
  }

  @Test
  @DisplayName("findHistories calculates totalPages via ceil(total/size)")
  void findHistories_calculatesTotalPages() {
    when(historyRepository.countByFilters(eq(0L), eq(0), isNull(), isNull())).thenReturn(Mono.just(25L));
    when(historyRepository.findPageByFilters(eq(0L), eq(0), isNull(), isNull(), eq(10), eq(0L)))
        .thenReturn(Flux.empty());

    StepVerifier.create(service.findHistories(null, null, null, null, 0, 10))
        .assertNext(page -> {
          assertThat(page.totalElements()).isEqualTo(25L);
          assertThat(page.totalPages()).isEqualTo(3);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findHistories passes date range to repository")
  void findHistories_withDateRange_passesToRepo() {
    LocalDateTime start = LocalDateTime.of(2026, 5, 1, 0, 0);
    LocalDateTime end = LocalDateTime.of(2026, 5, 31, 23, 59);

    when(historyRepository.countByFilters(eq(0L), eq(0), eq(start), eq(end))).thenReturn(Mono.just(5L));
    when(historyRepository.findPageByFilters(eq(0L), eq(0), eq(start), eq(end), anyInt(), anyLong()))
        .thenReturn(Flux.empty());

    StepVerifier.create(service.findHistories(null, start, end, null, 0, 20))
        .assertNext(page -> assertThat(page.totalElements()).isEqualTo(5L))
        .verifyComplete();
  }

  // --- findByIdempotencyKey ---

  @Test
  @DisplayName("findByIdempotencyKey returns history for existing key")
  void findByIdempotencyKey_existingKey_returnsHistory() {
    when(systemRepository.findByCode("midtrans")).thenReturn(Mono.just(system(10L, "midtrans")));
    when(historyRepository.findFirstByOutboundSystemIdAndIdempotencyKeyOrderByCreatedDateDesc(10L, "order-abc"))
        .thenReturn(Mono.just(history(10L, "order-abc", 201)));

    StepVerifier.create(service.findByIdempotencyKey("midtrans", "order-abc"))
        .assertNext(resp -> {
          assertThat(resp.outboundSystemId()).isEqualTo(10L);
          assertThat(resp.idempotencyKey()).isEqualTo("order-abc");
          assertThat(resp.responseStatus()).isEqualTo(201);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findByIdempotencyKey returns error when code not found")
  void findByIdempotencyKey_unknownCode_returnsError() {
    when(systemRepository.findByCode("no-system")).thenReturn(Mono.empty());

    StepVerifier.create(service.findByIdempotencyKey("no-system", "any-key"))
        .expectErrorMatches(e -> e instanceof OutboundSystemNotFoundException)
        .verify();
  }

  @Test
  @DisplayName("findByIdempotencyKey returns error when key not found")
  void findByIdempotencyKey_unknownKey_returnsError() {
    when(systemRepository.findByCode("xendit")).thenReturn(Mono.just(system(1L, "xendit")));
    when(historyRepository.findFirstByOutboundSystemIdAndIdempotencyKeyOrderByCreatedDateDesc(1L, "ghost-key"))
        .thenReturn(Mono.empty());

    StepVerifier.create(service.findByIdempotencyKey("xendit", "ghost-key"))
        .expectErrorMatches(e -> e instanceof IllegalArgumentException
            && e.getMessage().contains("code=xendit"))
        .verify();
  }

  // --- helpers ---

  private OutboundSystem system(Long id, String code) {
    OutboundSystem s = OutboundSystem.builder()
        .code(code)
        .name(code)
        .httpMethod("POST")
        .baseUrl("https://api.example.com")
        .apiPath("/v1/test")
        .active(true)
        .idempotencyTtlHours(24)
        .build();
    s.setId(id);
    return s;
  }

  private OutboundHistory history(long systemId, String key, int status) {
    return OutboundHistory.builder()
        .outboundSystemId(systemId)
        .idempotencyKey(key)
        .callerService("test-svc")
        .requestMethod("POST")
        .requestUrl("https://api.example.com/v1/test")
        .responseStatus(status)
        .responsePayload("{\"ok\":true}")
        .durationMs(100)
        .build();
  }
}
