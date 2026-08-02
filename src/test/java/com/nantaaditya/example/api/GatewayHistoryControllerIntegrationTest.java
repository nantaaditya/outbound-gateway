package com.nantaaditya.example.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
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
  private OutboundHostConfigurationRepository hostConfigRepository;
  @Autowired
  private OutboundHistoryRepository historyRepository;
  @Autowired
  private ObjectMapper objectMapper;

  private Long systemId;

  @BeforeEach
  void seed() {
    OutboundHostConfiguration hostConfig = hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl("https://api.xendit.co")
        .connectTimeOutMs(5000)
        .maxConnections(50)
        .pendingAcquireMaxCount(100)
        .pendingAcquireTimeoutMs(5000)
        .disableSslVerification(false)
        .useProxy(false)
        .build()).block();

    OutboundSystem system = systemRepository.save(OutboundSystem.builder()
        .code("xendit-test")
        .name("Xendit Test")
        .httpMethod("POST")
        .hostConfigurationId(hostConfig.getId())
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
    hostConfigRepository.deleteAll().block();
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
    assertThat(root.path("pagination").path("totalElements").asLong()).isPositive();
    assertThat(root.path("data").isArray()).isTrue();
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
    assertThat(root.path("pagination").path("totalElements").asLong()).isEqualTo(2L);
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
    assertThat(root.path("pagination").path("totalElements").asLong()).isEqualTo(1L);
    assertThat(root.path("data").get(0).path("responseStatus").asInt()).isEqualTo(500);
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
    assertThat(root.path("pagination").path("size").asInt()).isEqualTo(1);
    assertThat(root.path("data").size()).isEqualTo(1);
    assertThat(root.path("pagination").path("totalPages").asInt()).isEqualTo(2);
  }

  // --- GET /api/gateway/histories/{idempotencyKey} ---

  @Test
  @DisplayName("GET /api/gateway/history/{key} — returns single history by idempotency key")
  void findByIdempotencyKey_existingKey_returnsHistory() throws Exception {
    byte[] body = webTestClient.get()
        .uri("/api/gateway/history/xendit-test?idempotencyKey=idem-001")
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
  @DisplayName("GET /api/gateway/history/{key} — unknown key returns HTTP 400")
  void findByIdempotencyKey_unknownKey_returns400() {
    webTestClient.get()
        .uri("/api/gateway/history/xendit-test?idempotencyKey=ghost-key")
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  @DisplayName("GET /api/gateway/history/{key} — unknown code returns HTTP 400")
  void findByIdempotencyKey_unknownCode_returns400() {
    webTestClient.get()
        .uri("/api/gateway/history/no-system?idempotencyKey=idem-001")
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  @DisplayName("GET /api/gateway/history/{key} — blank code returns HTTP 400")
  void findByIdempotencyKey_blankCode_returns400() {
    webTestClient.get()
        .uri("/api/gateway/history/?idempotencyKey=idem-001")
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  @DisplayName("GET /api/gateway/histories — startDate in the past includes all seeded records")
  void findHistories_filterByStartDateInPast_includesSeededRecords() throws Exception {
    byte[] body = webTestClient.get()
        .uri("/api/gateway/histories?code=xendit-test&startDate=2020-01-01")
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("pagination").path("totalElements").asLong()).isEqualTo(2L);
  }

  @Test
  @DisplayName("GET /api/gateway/histories — startDate in the future returns empty result")
  void findHistories_filterByStartDateInFuture_returnsEmpty() throws Exception {
    byte[] body = webTestClient.get()
        .uri("/api/gateway/histories?code=xendit-test&startDate=2099-01-01")
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("pagination").path("totalElements").asLong()).isZero();
    assertThat(root.path("data").size()).isEqualTo(0);
  }

  @Test
  @DisplayName("GET /api/gateway/histories — out-of-bounds page returns empty content but correct totalElements")
  void findHistories_outOfBoundsPage_returnsEmptyContent() throws Exception {
    byte[] body = webTestClient.get()
        .uri("/api/gateway/histories?code=xendit-test&page=99&size=10")
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("pagination").path("totalElements").asLong()).isEqualTo(2L);
    assertThat(root.path("data").size()).isZero();
  }

  @Test
  @DisplayName("GET /api/gateway/histories — status filter without code returns matching records across all systems")
  void findHistories_filterByStatusOnly_returnsMatchingRecords() throws Exception {
    byte[] body = webTestClient.get()
        .uri("/api/gateway/histories?status=200")
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("pagination").path("totalElements").asLong()).isPositive();
    JsonNode first = root.path("data").get(0);
    assertThat(first.path("responseStatus").asInt()).isEqualTo(200);
  }

  @Test
  @DisplayName("GET /api/gateway/history/{key} — response includes all expected fields")
  void findByIdempotencyKey_existingKey_responseContainsAllFields() throws Exception {
    byte[] body = webTestClient.get()
        .uri("/api/gateway/history/xendit-test?idempotencyKey=idem-002")
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();

    JsonNode data = objectMapper.readTree(body).path("data");
    assertThat(data.path("idempotencyKey").asText()).isEqualTo("idem-002");
    assertThat(data.path("responseStatus").asInt()).isEqualTo(500);
    assertThat(data.path("callerService").asText()).isEqualTo("test-svc");
    assertThat(data.path("requestMethod").asText()).isEqualTo("POST");
    assertThat(data.path("durationMs").asInt()).isEqualTo(150);
    assertThat(data.path("outboundSystemId").asLong()).isEqualTo(systemId);
    assertThat(data.hasNonNull("createdDate")).isTrue();
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
