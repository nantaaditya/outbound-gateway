package com.nantaaditya.example.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundHistory;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

class OutboundHistoryRepositoryFilterTest extends BaseIntegrationTest {

  @Autowired
  private OutboundHistoryRepository repository;

  @BeforeEach
  void seed() {
    repository.save(history(1L, "key-A", 200)).block();
    repository.save(history(1L, "key-B", 500)).block();
    repository.save(history(2L, "key-C", 200)).block();
  }

  @AfterEach
  void cleanup() {
    repository.deleteAll().block();
  }

  // --- findByOutboundSystemIdAndIdempotencyKey ---

  @Test
  @DisplayName("findByIdempotencyKey returns record regardless of TTL window")
  void findByIdempotencyKey_existingKey_returns() {
    StepVerifier.create(
        repository.findFirstByOutboundSystemIdAndIdempotencyKeyOrderByCreatedDateDesc(1L, "key-A"))
        .assertNext(h -> {
          assertThat(h.getIdempotencyKey()).isEqualTo("key-A");
          assertThat(h.getResponseStatus()).isEqualTo(200);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findByIdempotencyKey returns empty for unknown key")
  void findByIdempotencyKey_unknownKey_empty() {
    StepVerifier.create(
        repository.findFirstByOutboundSystemIdAndIdempotencyKeyOrderByCreatedDateDesc(1L, "no-such-key"))
        .verifyComplete();
  }

  // --- findPageByFilters ---

  @Test
  @DisplayName("findPageByFilters with systemId=0 returns all records")
  void findPageByFilters_noSystemFilter_returnsAll() {
    StepVerifier.create(
        repository.findPageByFilters(0L, 0, null, null, 10, 0).collectList())
        .assertNext(list -> assertThat(list).hasSize(3))
        .verifyComplete();
  }

  @Test
  @DisplayName("findPageByFilters filters by systemId")
  void findPageByFilters_bySystemId_returnsMatching() {
    StepVerifier.create(
        repository.findPageByFilters(1L, 0, null, null, 10, 0).collectList())
        .assertNext(list -> {
          assertThat(list).hasSize(2);
          assertThat(list).allMatch(h -> h.getOutboundSystemId() == 1L);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findPageByFilters filters by response status")
  void findPageByFilters_byStatus_returnsMatching() {
    StepVerifier.create(
        repository.findPageByFilters(0L, 500, null, null, 10, 0).collectList())
        .assertNext(list -> {
          assertThat(list).hasSize(1);
          assertThat(list.get(0).getResponseStatus()).isEqualTo(500);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findPageByFilters with future startDate returns empty")
  void findPageByFilters_futureStartDate_returnsEmpty() {
    LocalDateTime future = LocalDateTime.now().plusHours(1);
    StepVerifier.create(
        repository.findPageByFilters(0L, 0, future, null, 10, 0).collectList())
        .assertNext(list -> assertThat(list).isEmpty())
        .verifyComplete();
  }

  @Test
  @DisplayName("findPageByFilters with past endDate returns empty")
  void findPageByFilters_pastEndDate_returnsEmpty() {
    LocalDateTime past = LocalDateTime.now().minusHours(1);
    StepVerifier.create(
        repository.findPageByFilters(0L, 0, null, past, 10, 0).collectList())
        .assertNext(list -> assertThat(list).isEmpty())
        .verifyComplete();
  }

  @Test
  @DisplayName("findPageByFilters respects page size and offset")
  void findPageByFilters_pagination_respectsSizeAndOffset() {
    StepVerifier.create(
        repository.findPageByFilters(0L, 0, null, null, 2, 0).collectList())
        .assertNext(list -> assertThat(list).hasSize(2))
        .verifyComplete();

    StepVerifier.create(
        repository.findPageByFilters(0L, 0, null, null, 2, 2).collectList())
        .assertNext(list -> assertThat(list).hasSize(1))
        .verifyComplete();
  }

  // --- countByFilters ---

  @Test
  @DisplayName("countByFilters with no filter returns total count")
  void countByFilters_noFilter_returnsTotal() {
    StepVerifier.create(repository.countByFilters(0L, 0, null, null))
        .expectNext(3L)
        .verifyComplete();
  }

  @Test
  @DisplayName("countByFilters by systemId returns correct count")
  void countByFilters_bySystemId_returnsCount() {
    StepVerifier.create(repository.countByFilters(2L, 0, null, null))
        .expectNext(1L)
        .verifyComplete();
  }

  @Test
  @DisplayName("countByFilters by status returns correct count")
  void countByFilters_byStatus_returnsCount() {
    StepVerifier.create(repository.countByFilters(0L, 200, null, null))
        .expectNext(2L)
        .verifyComplete();
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
