package com.nantaaditya.example.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.helper.DateTimeHelper;
import com.nantaaditya.example.helper.TsidHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.request.admin.CreateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpdateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpsertAuthRequest;
import com.nantaaditya.example.model.request.admin.UpsertHeadersRequest;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest.BulkHead;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest.CircuitBreaker;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest.RateLimiter;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest.Retry;
import com.nantaaditya.example.model.request.admin.UpsertResponseMapperRequest;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.repository.EventLogRepository;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class AdminOutboundControllerIntegrationTest extends BaseIntegrationTest {

  static WireMockServer wireMock;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @Autowired
  private OutboundSystemRepository systemRepository;
  @Autowired
  private OutboundResilienceConfigurationRepository resilienceRepository;
  @Autowired
  private OutboundAuthConfigurationRepository authRepository;
  @Autowired
  private OutboundHistoryRepository historyRepository;
  @Autowired
  private OutboundHostConfigurationRepository hostConfigRepository;
  @Autowired
  private EventLogRepository eventLogRepository;
  @Autowired
  private ObjectMapper objectMapper;

  @AfterEach
  void cleanup() {
    resilienceRepository.deleteAll().block();
    authRepository.deleteAll().block();
    historyRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    eventLogRepository.deleteAll().block();
  }

  // --- 1. CREATE ---

  @Test
  @DisplayName("POST /api/outbound — creates system and event_log")
  void create_validRequest_createsSystemAndEventLog() throws Exception {
    String requestId = TsidHelper.generateStringId();
    CreateOutboundSystemRequest req = new CreateOutboundSystemRequest(
        "admin-create-test", "Admin Create Test", "desc",
        "POST", "http://localhost:9999", "/v1/test",
        true, 24);

    byte[] body = webTestClient.post()
        .uri("/api/outbound")
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

    // Assertion 1: HTTP response
    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").path("code").asText()).isEqualTo("admin-create-test");

    // Assertion 2: DB row exists
    OutboundSystem saved = systemRepository.findByCode("admin-create-test").block();
    assertThat(saved).isNotNull();
    assertThat(saved.getName()).isEqualTo("Admin Create Test");

    // Assertion 3: event_log row exists
    awaitEventLog(requestId);
  }

  // --- 2. LIST ---

  @Test
  @DisplayName("GET /api/outbound — lists systems and event_log")
  void list_existingSystem_returnsListAndEventLog() throws Exception {
    seedSystem("admin-list-test");
    String requestId = TsidHelper.generateStringId();

    byte[] body = webTestClient.get()
        .uri("/api/outbound")
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    // Assertion 1: non-empty list
    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").isArray()).isTrue();
    assertThat(root.path("data").size()).isGreaterThanOrEqualTo(1);

    // Assertion 2: DB has system
    assertThat(systemRepository.findByCode("admin-list-test").block()).isNotNull();

    // Assertion 3: event_log
    awaitEventLog(requestId);
  }

  // --- 3. GET DETAIL ---

  @Test
  @DisplayName("GET /api/outbound/{code} — returns detail and event_log")
  void getDetail_existingCode_returnsDetailAndEventLog() throws Exception {
    OutboundSystem system = seedSystem("admin-detail-test");
    resilienceRepository.save(OutboundResilienceConfiguration.builder()
        .outboundSystemId(system.getId())
        .readTimeOutMs(5000)
        .circuitBreakerEnabled(false)
        .bulkheadEnabled(false)
        .rateLimiterEnabled(false)
        .retryEnabled(false)
        .build()).block();
    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.NONE)
        .build()).block();

    String requestId = TsidHelper.generateStringId();

    byte[] body = webTestClient.get()
        .uri("/api/outbound/admin-detail-test")
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    // Assertion 1: response contains system details
    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").path("code").asText()).isEqualTo("admin-detail-test");

    // Assertion 2: DB row exists
    assertThat(systemRepository.findByCode("admin-detail-test").block()).isNotNull();

    // Assertion 3: event_log
    awaitEventLog(requestId);
  }

  // --- 4. UPDATE ---

  @Test
  @DisplayName("PUT /api/outbound/{code} — updates system and event_log")
  void update_existingCode_updatesSystemAndEventLog() throws Exception {
    seedSystem("admin-update-test");
    String requestId = TsidHelper.generateStringId();

    UpdateOutboundSystemRequest req = new UpdateOutboundSystemRequest(
        "Updated Name", "updated desc", "GET",
        "http://updated-host.com", "/v2/updated", true, 48);

    byte[] body = webTestClient.put()
        .uri("/api/outbound/admin-update-test")
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

    // Assertion 1: response contains updated name
    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").path("name").asText()).isEqualTo("Updated Name");

    // Assertion 2: DB row updated
    OutboundSystem updated = systemRepository.findByCode("admin-update-test").block();
    assertThat(updated).isNotNull();
    assertThat(updated.getName()).isEqualTo("Updated Name");
    assertThat(updated.getIdempotencyTtlHours()).isEqualTo(48);

    // Assertion 3: event_log
    awaitEventLog(requestId);
  }

  // --- 5. DELETE ---

  @Test
  @DisplayName("DELETE /api/outbound/{code} — deletes system and event_log")
  void delete_existingCode_deletesSystemAndEventLog() throws Exception {
    OutboundSystem system = seedSystem("admin-delete-test");
    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.NONE)
        .build()).block();

    String requestId = TsidHelper.generateStringId();

    byte[] body = webTestClient.delete()
        .uri("/api/outbound/admin-delete-test")
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    // Assertion 1: success true
    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").asBoolean()).isTrue();

    // Assertion 2: system deleted from DB
    assertThat(systemRepository.findByCode("admin-delete-test").block()).isNull();

    // Assertion 3: event_log
    awaitEventLog(requestId);
  }

  // --- 6. UPSERT RESILIENCE ---

  @Test
  @DisplayName("POST /api/outbound/{code}/resilience — saves config and event_log")
  void upsertResilience_validRequest_savesConfigAndEventLog() throws Exception {
    OutboundSystem system = seedSystem("admin-resilience-test");
    String requestId = TsidHelper.generateStringId();

    UpsertResilienceRequest req = new UpsertResilienceRequest(
        5000,
        new CircuitBreaker(50f, 100f, 60000, 10, 100, 60000),
        new BulkHead( 25, 0),
        new RateLimiter( 100, 1000, 0),
        new Retry(3, 500, Set.of("500","502","503"))
    );

    webTestClient.post()
        .uri("/api/outbound/admin-resilience-test/resilience")
        .contentType(MediaType.APPLICATION_JSON)
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .bodyValue(req)
        .exchange()
        .expectStatus().isOk();

    // Assertion 1: DB row exists
    OutboundResilienceConfiguration saved =
        resilienceRepository.findByOutboundSystemId(system.getId()).block();
    assertThat(saved).isNotNull();

    // Assertion 2: readTimeOutMs saved correctly
    assertThat(saved.getReadTimeOutMs()).isEqualTo(5000);

    // Assertion 3: event_log
    awaitEventLog(requestId);
  }

  // --- 7. UPSERT HEADERS ---

  @Test
  @DisplayName("POST /api/outbound/{code}/headers — saves headers and event_log")
  void upsertHeaders_validRequest_savesHeadersAndEventLog() throws Exception {
    seedSystem("admin-headers-test");
    String requestId = TsidHelper.generateStringId();

    UpsertHeadersRequest req = new UpsertHeadersRequest(
        Map.of("X-Custom-Header", List.of("value1", "value2")));

    byte[] body = webTestClient.post()
        .uri("/api/outbound/admin-headers-test/headers")
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

    // Assertion 1: response success
    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").asBoolean()).isTrue();

    // Assertion 2: system still exists
    assertThat(systemRepository.findByCode("admin-headers-test").block()).isNotNull();

    // Assertion 3: event_log
    awaitEventLog(requestId);
  }

  // --- 8. UPSERT AUTH ---

  @Test
  @DisplayName("POST /api/outbound/{code}/auth — encrypts and saves and event_log")
  void upsertAuth_apiKey_encryptsAndSavesAndEventLog() throws Exception {
    OutboundSystem system = seedSystem("admin-auth-test");
    String requestId = TsidHelper.generateStringId();

    UpsertAuthRequest req = new UpsertAuthRequest(
        AuthType.API_KEY, "my-secret-api-key", null, null,
        false, null, null, null, null, null, "X-Api-Key", null, null);

    webTestClient.post()
        .uri("/api/outbound/admin-auth-test/auth")
        .contentType(MediaType.APPLICATION_JSON)
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .bodyValue(req)
        .exchange()
        .expectStatus().isOk();

    // Assertion 1: auth row saved
    OutboundAuthConfiguration savedAuth =
        authRepository.findByOutboundSystemId(system.getId()).block();
    assertThat(savedAuth).isNotNull();

    // Assertion 2: stored apiKey is encrypted (NOT equal to plaintext bytes)
    byte[] storedApiKey = savedAuth.getApiKey();
    assertThat(storedApiKey).isNotNull();
    assertThat(new String(storedApiKey)).isNotEqualTo("my-secret-api-key");

    // Assertion 3: event_log
    awaitEventLog(requestId);
  }

  // --- 9. UPSERT RESPONSE MAPPER ---

  @Test
  @DisplayName("POST /api/outbound/{code}/response-mapper — saves mapper and event_log")
  void upsertResponseMapper_validRequest_savesMapperAndEventLog() throws Exception {
    seedSystem("admin-mapper-test");
    String requestId = TsidHelper.generateStringId();

    UpsertResponseMapperRequest req = new UpsertResponseMapperRequest(
        ConverterType.NONE, null, Map.of("200", 200), true);

    byte[] body = webTestClient.post()
        .uri("/api/outbound/admin-mapper-test/response-mapper")
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

    // Assertion 1: success
    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").asBoolean()).isTrue();

    // Assertion 2: system accessible
    assertThat(systemRepository.findByCode("admin-mapper-test").block()).isNotNull();

    // Assertion 3: event_log
    awaitEventLog(requestId);
  }

  // --- 10. RELOAD ---

  @Test
  @DisplayName("POST /api/outbound/{code}/reload — evicts cache and event_log")
  void reload_existingCode_evictsCacheAndEventLog() throws Exception {
    seedSystem("admin-reload-test");
    String requestId = TsidHelper.generateStringId();

    byte[] body = webTestClient.post()
        .uri("/api/outbound/admin-reload-test/reload")
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    // Assertion 1: success true
    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").asBoolean()).isTrue();

    // Assertion 2: system still exists
    assertThat(systemRepository.findByCode("admin-reload-test").block()).isNotNull();

    // Assertion 3: event_log
    awaitEventLog(requestId);
  }

  // --- 11. TEST CONNECTION ---

  @Test
  @DisplayName("POST /api/outbound/{code}/test — WireMock 200, returns success and event_log")
  void testConnection_reachable_returnsSuccessAndEventLog() throws Exception {
    String wireMockPath = "/v1/test-conn";
    wireMock.stubFor(get(urlEqualTo(wireMockPath))
        .willReturn(aResponse()
            .withStatus(200)
            .withBody("{\"ok\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    seedSystem("admin-test-conn", baseUrl, wireMockPath, "GET");

    String requestId = TsidHelper.generateStringId();

    byte[] body = webTestClient.post()
        .uri("/api/outbound/admin-test-conn/test")
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    // Assertion 1: success=true, httpStatus=200
    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").path("success").asBoolean()).isTrue();
    assertThat(root.path("data").path("httpStatus").asInt()).isEqualTo(200);

    // Assertion 2: system exists
    assertThat(systemRepository.findByCode("admin-test-conn").block()).isNotNull();

    // Assertion 3: event_log
    awaitEventLog(requestId);
  }

  // --- 12. GET METRICS (no history) ---

  @Test
  @DisplayName("GET /api/outbound/{code}/metrics — no history returns zero metrics and event_log")
  void getMetrics_noHistory_returnsZeroMetricsAndEventLog() throws Exception {
    seedSystem("admin-metrics-zero");
    String requestId = TsidHelper.generateStringId();

    byte[] body = webTestClient.get()
        .uri("/api/outbound/admin-metrics-zero/metrics")
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    // Assertion 1: response with zero calls
    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").path("totalCalls").asLong()).isEqualTo(0L);
    assertThat(root.path("data").path("code").asText()).isEqualTo("admin-metrics-zero");

    // Assertion 2: system exists
    assertThat(systemRepository.findByCode("admin-metrics-zero").block()).isNotNull();

    // Assertion 3: event_log
    awaitEventLog(requestId);
  }

  // --- 13. GET METRICS (with history) ---

  @Test
  @DisplayName("GET /api/outbound/{code}/metrics — with history returns p90/p95/p99 and event_log")
  void getMetrics_withHistory_returnsp90p95p99AndEventLog() throws Exception {
    OutboundSystem system = seedSystem("admin-metrics-with-hist");

    // Seed 10 histories with known durations
    int[] durations = {100, 150, 200, 250, 300, 350, 400, 450, 500, 600};
    for (int d : durations) {
      historyRepository.save(OutboundHistory.builder()
          .outboundSystemId(system.getId())
          .requestMethod("POST")
          .requestUrl("http://example.com/v1")
          .responseStatus(200)
          .durationMs(d)
          .build()).block();
    }

    String requestId = TsidHelper.generateStringId();

    byte[] body = webTestClient.get()
        .uri("/api/outbound/admin-metrics-with-hist/metrics")
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    // Assertion 1: total calls and p-values
    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").path("totalCalls").asLong()).isEqualTo(10L);
    assertThat(root.path("data").path("p90DurationMs").asLong()).isGreaterThanOrEqualTo(450L);
    assertThat(root.path("data").path("p99DurationMs").asLong()).isGreaterThanOrEqualTo(550L);

    // Assertion 2: history count in DB
    assertThat(historyRepository.countByFilters(system.getId(), 0, null, null).block())
        .isEqualTo(10L);

    // Assertion 3: event_log
    awaitEventLog(requestId);
  }

  // --- 14. GET DETAIL with host config ---

  @Test
  @DisplayName("GET /api/outbound/{code} — returns hostConfig when configured")
  void getDetail_withHostConfig_includesHostConfigDetail() throws Exception {
    seedSystem("admin-detail-hostcfg");
    seedHostConfig("http://localhost:9999");
    String requestId = TsidHelper.generateStringId();

    byte[] body = webTestClient.get()
        .uri("/api/outbound/admin-detail-hostcfg")
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
    assertThat(root.path("data").path("hostConfig").isNull()).isFalse();
    assertThat(root.path("data").path("hostConfig").path("baseUrl").asText())
        .isEqualTo("http://localhost:9999");
    assertThat(root.path("data").path("hostConfig").path("maxConnections").asInt()).isEqualTo(50);

    awaitEventLog(requestId);
  }

  // --- 15. LIST with host config ---

  @Test
  @DisplayName("GET /api/outbound — returns hostConfig in each item when configured")
  void list_withHostConfig_includesHostConfigInItems() throws Exception {
    seedSystem("admin-list-hostcfg");
    seedHostConfig("http://localhost:9999");
    String requestId = TsidHelper.generateStringId();

    byte[] body = webTestClient.get()
        .uri("/api/outbound")
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
    JsonNode first = root.path("data").get(0);
    assertThat(first.path("hostConfig").isNull()).isFalse();
    assertThat(first.path("hostConfig").path("baseUrl").asText()).isEqualTo("http://localhost:9999");

    awaitEventLog(requestId);
  }

  // --- Helpers ---

  private OutboundSystem seedSystem(String code) {
    return seedSystem(code, "http://localhost:9999", "/v1/test", "POST");
  }

  private OutboundSystem seedSystem(String code, String baseUrl, String apiPath, String method) {
    return systemRepository.save(OutboundSystem.builder()
        .code(code)
        .name(code + "-name")
        .httpMethod(method)
        .baseUrl(baseUrl)
        .apiPath(apiPath)
        .active(true)
        .idempotencyTtlHours(24)
        .build()).block();
  }

  private void seedHostConfig(String baseUrl) {
    hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl(baseUrl)
        .connectTimeOutMs(3000)
        .maxConnections(50)
        .pendingAcquireMaxCount(100)
        .pendingAcquireTimeoutMs(3000)
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
