package com.nantaaditya.example.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.helper.DateTimeHelper;
import com.nantaaditya.example.helper.TsidHelper;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.request.admin.CreateHostConfigRequest;
import com.nantaaditya.example.model.request.admin.UpdateHostConfigRequest;
import com.nantaaditya.example.repository.EventLogRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import java.time.Duration;
import java.time.ZonedDateTime;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class OutboundHostControllerIntegrationTest extends BaseIntegrationTest {

  @Autowired
  private OutboundHostConfigurationRepository hostConfigRepository;

  @Autowired
  private EventLogRepository eventLogRepository;

  @Autowired
  private ObjectMapper objectMapper;

  @AfterEach
  void cleanup() {
    hostConfigRepository.deleteAll().block();
    eventLogRepository.deleteAll().block();
  }

  // --- 1. CREATE ---

  @Test
  @DisplayName("POST /api/outbound/host — creates host config and event_log")
  void create_validRequest_createsHostConfigAndEventLog() throws Exception {
    String requestId = TsidHelper.generateStringId();
    CreateHostConfigRequest req = new CreateHostConfigRequest(
        "http://host-create-test.example.com",
        5000, 100, 200, 30000,
        false, false, null, 0, null, null
    );

    byte[] body = webTestClient.post()
        .uri("/api/outbound/host")
        .contentType(MediaType.APPLICATION_JSON)
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .bodyValue(req)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").path("baseUrl").asText())
        .isEqualTo("http://host-create-test.example.com");
    assertThat(root.path("data").path("maxConnections").asInt()).isEqualTo(100);

    OutboundHostConfiguration saved = hostConfigRepository
        .findByBaseUrl("http://host-create-test.example.com").block();
    assertThat(saved).isNotNull();
    assertThat(saved.getMaxConnections()).isEqualTo(100);

    awaitEventLog(requestId);
  }

  @Test
  @DisplayName("POST /api/outbound/host — useProxy=true without proxyHost returns 400")
  void create_useProxyWithoutProxyHost_returns400() {
    String requestId = TsidHelper.generateStringId();
    CreateHostConfigRequest req = new CreateHostConfigRequest(
        "http://host-proxy-invalid.example.com",
        5000, 100, 200, 30000,
        false, true, null, 0, null, null
    );

    webTestClient.post()
        .uri("/api/outbound/host")
        .contentType(MediaType.APPLICATION_JSON)
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .bodyValue(req)
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  @DisplayName("POST /api/outbound/host — duplicate baseUrl returns 400")
  void create_duplicateBaseUrl_returns400() {
    seedHostConfig("http://host-dup.example.com");
    String requestId = TsidHelper.generateStringId();
    CreateHostConfigRequest req = new CreateHostConfigRequest(
        "http://host-dup.example.com",
        5000, 100, 200, 30000,
        false, false, null, 0, null, null
    );

    webTestClient.post()
        .uri("/api/outbound/host")
        .contentType(MediaType.APPLICATION_JSON)
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .bodyValue(req)
        .exchange()
        .expectStatus().isBadRequest();
  }

  // --- 2. LIST ---

  @Test
  @DisplayName("GET /api/outbound/host — lists host configs and event_log")
  void list_existingConfigs_returnsListAndEventLog() throws Exception {
    seedHostConfig("http://host-list-a.example.com");
    seedHostConfig("http://host-list-b.example.com");
    String requestId = TsidHelper.generateStringId();

    byte[] body = webTestClient.get()
        .uri("/api/outbound/host")
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").isArray()).isTrue();
    assertThat(root.path("data").size()).isGreaterThanOrEqualTo(2);

    awaitEventLog(requestId);
  }

  // --- 3. GET BY ID ---

  @Test
  @DisplayName("GET /api/outbound/host/{id} — returns host config and event_log")
  void getById_existingId_returnsHostConfigAndEventLog() throws Exception {
    OutboundHostConfiguration cfg = seedHostConfig("http://host-getbyid.example.com");
    String requestId = TsidHelper.generateStringId();

    byte[] body = webTestClient.get()
        .uri("/api/outbound/host/" + cfg.getId())
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").path("id").asLong()).isEqualTo(cfg.getId());
    assertThat(root.path("data").path("baseUrl").asText())
        .isEqualTo("http://host-getbyid.example.com");

    awaitEventLog(requestId);
  }

  @Test
  @DisplayName("GET /api/outbound/host/{id} — unknown id returns 400")
  void getById_unknownId_returns400() {
    String requestId = TsidHelper.generateStringId();

    webTestClient.get()
        .uri("/api/outbound/host/999999")
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isBadRequest();
  }

  // --- 4. UPDATE ---

  @Test
  @DisplayName("PUT /api/outbound/host/{id} — updates host config and event_log")
  void update_existingId_updatesConfigAndEventLog() throws Exception {
    OutboundHostConfiguration cfg = seedHostConfig("http://host-update.example.com");
    String requestId = TsidHelper.generateStringId();

    UpdateHostConfigRequest req = new UpdateHostConfigRequest(
        8000, 200, 400, 45000,
        false, false, null, 0, null, null
    );

    byte[] body = webTestClient.put()
        .uri("/api/outbound/host/" + cfg.getId())
        .contentType(MediaType.APPLICATION_JSON)
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .bodyValue(req)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").path("connectTimeOutMs").asInt()).isEqualTo(8000);
    assertThat(root.path("data").path("maxConnections").asInt()).isEqualTo(200);

    OutboundHostConfiguration updated = hostConfigRepository.findById(cfg.getId()).block();
    assertThat(updated).isNotNull();
    assertThat(updated.getConnectTimeOutMs()).isEqualTo(8000);

    awaitEventLog(requestId);
  }

  // --- 5. RELOAD ---

  @Test
  @DisplayName("POST /api/outbound/host/{id}/reload — evicts WebClient pool and event_log")
  void reload_existingId_evictsPoolAndEventLog() throws Exception {
    OutboundHostConfiguration cfg = seedHostConfig("http://host-reload.example.com");
    String requestId = TsidHelper.generateStringId();

    byte[] body = webTestClient.post()
        .uri("/api/outbound/host/" + cfg.getId() + "/reload")
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").asBoolean()).isTrue();

    awaitEventLog(requestId);
  }

  // --- Helpers ---

  private OutboundHostConfiguration seedHostConfig(String baseUrl) {
    return hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl(baseUrl)
        .connectTimeOutMs(5000)
        .maxConnections(100)
        .pendingAcquireMaxCount(200)
        .pendingAcquireTimeoutMs(30000)
        .disableSslVerification(false)
        .useProxy(false)
        .build()).block();
  }

  private void awaitEventLog(String requestId) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .until(() -> eventLogRepository.findByRequestId(requestId).block() != null);
  }
}
