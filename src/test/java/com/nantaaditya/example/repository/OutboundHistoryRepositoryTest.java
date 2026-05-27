package com.nantaaditya.example.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundHistory;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

class OutboundHistoryRepositoryTest extends BaseIntegrationTest {

  @Autowired
  private OutboundHistoryRepository outboundHistoryRepository;

  @AfterEach
  void cleanup() {
    outboundHistoryRepository.deleteAll().block();
  }

  @Test
  @DisplayName("save and findByOutboundSystemIdAndIdempotencyKeyAndCreatedDateAfter returns saved history")
  void save_withIdempotencyKey_foundByKey() {
    OutboundHistory history = OutboundHistory.builder()
        .outboundSystemId(1L)
        .idempotencyKey("idem-key-001")
        .callerService("payment-service")
        .requestMethod("POST")
        .requestUrl("https://api.example.com/v1/charge")
        .requestPayload("{\"amount\":10000}")
        .responseStatus(200)
        .responsePayload("{\"status\":\"success\"}")
        .durationMs(120)
        .build();

    StepVerifier.create(
        outboundHistoryRepository.save(history)
            .then(outboundHistoryRepository
                .findFirstByOutboundSystemIdAndIdempotencyKeyAndCreatedDateAfterOrderByCreatedDateDesc(
                    1L, "idem-key-001", LocalDateTime.now().minusHours(1)))
    )
        .assertNext(found -> {
          assertThat(found.getIdempotencyKey()).isEqualTo("idem-key-001");
          assertThat(found.getCallerService()).isEqualTo("payment-service");
          assertThat(found.getResponseStatus()).isEqualTo(200);
          assertThat(found.getDurationMs()).isEqualTo(120);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findByKey returns empty when key not found")
  void findByKey_unknownKey_returnsEmpty() {
    StepVerifier.create(
        outboundHistoryRepository.findFirstByOutboundSystemIdAndIdempotencyKeyAndCreatedDateAfterOrderByCreatedDateDesc(
            1L, "no-such-key", LocalDateTime.now().minusHours(1))
    )
        .verifyComplete();
  }

  @Test
  @DisplayName("findByKey returns empty when record is expired (cutoff in future)")
  void findByKey_expiredRecord_returnsEmpty() {
    OutboundHistory history = OutboundHistory.builder()
        .outboundSystemId(2L)
        .idempotencyKey("expired-key")
        .requestMethod("POST")
        .requestUrl("https://api.example.com/v1/charge")
        .responseStatus(200)
        .durationMs(50)
        .build();

    StepVerifier.create(
        outboundHistoryRepository.save(history)
            .then(outboundHistoryRepository
                .findFirstByOutboundSystemIdAndIdempotencyKeyAndCreatedDateAfterOrderByCreatedDateDesc(
                    2L, "expired-key", LocalDateTime.now().plusHours(1)))
    )
        .verifyComplete();
  }
}
