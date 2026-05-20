package com.nantaaditya.example.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GatewayHistoryControllerIntegrationTest extends BaseIntegrationTest {

  @Autowired
  private OutboundSystemRepository systemRepository;
  @Autowired
  private OutboundHistoryRepository historyRepository;
  @Autowired
  private ObjectMapper objectMapper;

  private Long systemId;

  @BeforeEach
  void seed() {
    OutboundSystem system = systemRepository.save(OutboundSystem.builder()
        .code("xendit-test")
        .name("Xendit Test")
        .httpMethod("POST")
        .baseUrl("https://api.xendit.co")
        .apiPath("/v2/charges")
        .active(true)
        .idempotencyTtlHours(24)
        .build()).block();

    systemId = system.getId();

    historyRepository.save(history(systemId, "idem-001", 200)).block();
    historyRepository.save(history(systemId, "idem-002", 500)).block();
  }

  @AfterEach
  void cleanup() {
    historyRepository.deleteAll().block();
    systemRepository.deleteAll().block();
  }

  // --- GET /api/gateway/histories ---

  @Test
  @DisplayName("GET /api/gateway/histories — returns paginated list with default params")
  void findHistories_defaultParams_returnsPaginatedList() throws Exception {
    byte[] body = webTestClient.get()
        .uri("/api/gateway/histories")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").path("totalElements").asLong()).isGreaterThanOrEqualTo(2L);
    assertThat(root.path("data").path("content").isArray()).isTrue();
  }

  @Test
  @DisplayName("GET /api/gateway/histories — filters by code")
  void findHistories_filterByCode_returnsMatchingRecords() throws Exception {
    byte[] body = webTestClient.get()
        .uri("/api/gateway/histories?code=xendit-test")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("data").path("totalElements").asLong()).isEqualTo(2L);
  }

  @Test
  @DisplayName("GET /api/gateway/histories — filters by status")
  void findHistories_filterByStatus_returnsMatchingRecords() throws Exception {
    byte[] body = webTestClient.get()
        .uri("/api/gateway/histories?code=xendit-test&status=500")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("data").path("totalElements").asLong()).isEqualTo(1L);
    assertThat(root.path("data").path("content").get(0).path("responseStatus").asInt()).isEqualTo(500);
  }

  @Test
  @DisplayName("GET /api/gateway/histories — unknown code returns HTTP 400")
  void findHistories_unknownCode_returns400() {
    webTestClient.get()
        .uri("/api/gateway/histories?code=no-such-code")
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  @DisplayName("GET /api/gateway/histories — respects page and size params")
  void findHistories_withPageAndSize_respectsPagination() throws Exception {
    byte[] body = webTestClient.get()
        .uri("/api/gateway/histories?code=xendit-test&page=0&size=1")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("data").path("size").asInt()).isEqualTo(1);
    assertThat(root.path("data").path("content").size()).isEqualTo(1);
    assertThat(root.path("data").path("totalPages").asInt()).isEqualTo(2);
  }

  // --- GET /api/gateway/histories/{idempotencyKey} ---

  @Test
  @DisplayName("GET /api/gateway/histories/{key} — returns single history by idempotency key")
  void findByIdempotencyKey_existingKey_returnsHistory() throws Exception {
    byte[] body = webTestClient.get()
        .uri("/api/gateway/histories/idem-001?code=xendit-test")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").path("idempotencyKey").asText()).isEqualTo("idem-001");
    assertThat(root.path("data").path("responseStatus").asInt()).isEqualTo(200);
    assertThat(root.path("data").path("requestPayload").path("amount").asInt()).isEqualTo(10000);
    assertThat(root.path("data").path("responsePayload").path("ok").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("GET /api/gateway/histories/{key} — unknown key returns HTTP 400")
  void findByIdempotencyKey_unknownKey_returns400() {
    webTestClient.get()
        .uri("/api/gateway/histories/ghost-key?code=xendit-test")
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  @DisplayName("GET /api/gateway/histories/{key} — unknown code returns HTTP 400")
  void findByIdempotencyKey_unknownCode_returns400() {
    webTestClient.get()
        .uri("/api/gateway/histories/idem-001?code=no-system")
        .exchange()
        .expectStatus().isBadRequest();
  }

  // --- helpers ---

  private OutboundHistory history(long sysId, String key, int status) {
    return OutboundHistory.builder()
        .outboundSystemId(sysId)
        .idempotencyKey(key)
        .callerService("test-svc")
        .requestMethod("POST")
        .requestUrl("https://api.xendit.co/v2/charges")
        .requestPayload("{\"amount\":10000}")
        .responseStatus(status)
        .responsePayload("{\"ok\":true}")
        .durationMs(150)
        .build();
  }
}
