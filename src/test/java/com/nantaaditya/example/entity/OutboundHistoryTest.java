package com.nantaaditya.example.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboundHistoryTest {

  @Test
  @DisplayName("builder creates entity with all fields set")
  void builder_allFields_entityCreated() {
    OutboundHistory history = OutboundHistory.builder()
        .outboundSystemId(1L)
        .idempotencyKey("key-abc-123")
        .callerService("payment-service")
        .requestId("req-001")
        .requestMethod("POST")
        .requestUrl("https://api.example.com/v1/payment")
        .requestHeaders(java.util.Map.of("Content-Type", java.util.List.of("application/json")))
        .requestPayload("{\"amount\":10000}")
        .responseStatus(200)
        .responseHeaders(java.util.Map.of("X-Request-Id", java.util.List.of("req-001")))
        .responsePayload("{\"status\":\"success\"}")
        .durationMs(234)
        .convertedStatus(200)
        .convertedResponse("{\"status\":\"success\"}")
        .build();

    assertThat(history.getOutboundSystemId()).isEqualTo(1L);
    assertThat(history.getIdempotencyKey()).isEqualTo("key-abc-123");
    assertThat(history.getCallerService()).isEqualTo("payment-service");
    assertThat(history.getRequestMethod()).isEqualTo("POST");
    assertThat(history.getResponseStatus()).isEqualTo(200);
    assertThat(history.getDurationMs()).isEqualTo(234);
  }

  @Test
  @DisplayName("isSuccessful returns true for 2xx status")
  void isSuccessful_2xxStatus_returnsTrue() {
    assertThat(historyWithStatus(200).isSuccessful()).isTrue();
    assertThat(historyWithStatus(201).isSuccessful()).isTrue();
    assertThat(historyWithStatus(204).isSuccessful()).isTrue();
  }

  @Test
  @DisplayName("isSuccessful returns false for non-2xx status")
  void isSuccessful_non2xxStatus_returnsFalse() {
    assertThat(historyWithStatus(400).isSuccessful()).isFalse();
    assertThat(historyWithStatus(404).isSuccessful()).isFalse();
    assertThat(historyWithStatus(500).isSuccessful()).isFalse();
  }

  @Test
  @DisplayName("hasIdempotencyKey returns true when key is set")
  void hasIdempotencyKey_keySet_returnsTrue() {
    OutboundHistory history = OutboundHistory.builder()
        .outboundSystemId(1L)
        .idempotencyKey("key-123")
        .build();

    assertThat(history.hasIdempotencyKey()).isTrue();
  }

  @Test
  @DisplayName("hasIdempotencyKey returns false when key is null")
  void hasIdempotencyKey_nullKey_returnsFalse() {
    OutboundHistory history = OutboundHistory.builder()
        .outboundSystemId(1L)
        .idempotencyKey(null)
        .build();

    assertThat(history.hasIdempotencyKey()).isFalse();
  }

  private OutboundHistory historyWithStatus(int status) {
    return OutboundHistory.builder()
        .outboundSystemId(1L)
        .responseStatus(status)
        .build();
  }
}
