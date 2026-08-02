package com.nantaaditya.example.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.helper.DateTimeHelper;
import com.nantaaditya.example.helper.TsidHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.request.admin.CreateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpsertAuthRequest;
import com.nantaaditya.example.model.request.admin.UpsertHeadersRequest;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest.BulkHead;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest.CircuitBreaker;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest.RateLimiter;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest.Retry;
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
import org.springframework.test.web.reactive.server.WebTestClient;

class AdminGatewayFlowPermutationTest extends BaseIntegrationTest {

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
  private OutboundHostConfigurationRepository hostConfigRepository;
  @Autowired
  private OutboundHistoryRepository historyRepository;
  @Autowired
  private EventLogRepository eventLogRepository;
  @Autowired
  private ObjectMapper objectMapper;

  @AfterEach
  void cleanup() {
    resilienceRepository.deleteAll().block();
    authRepository.deleteAll().block();
    historyRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    eventLogRepository.deleteAll().block();
  }

  // --- Permutation 1: Register → Execute → History → Delete ---

  @Test
  @DisplayName("Permutation 1: full register-execute-history-delete flow")
  void permutation1_registerExecuteHistoryDelete() throws Exception {
    String code = "perm1-flow";
    String baseUrl = "http://localhost:" + wireMock.port();
    wireMock.stubFor(post(urlEqualTo("/v1/perm1"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"result\":\"ok\"}")));

    // Step 1: create system (host_configuration_id is a mandatory FK — seed it first)
    OutboundHostConfiguration hostConfig = seedOrGetHostConfig(baseUrl);
    String rid1 = newRequestId();
    CreateOutboundSystemRequest createReq = new CreateOutboundSystemRequest(
        code, code + "-name", "desc", "POST", hostConfig.getId(), "/v1/perm1", true, 24);
    doPost("/api/outbound", createReq, rid1).expectStatus().isOk();
    awaitEventLog(rid1);

    OutboundSystem system = systemRepository.findByCode(code).block();
    assertThat(system).isNotNull();

    // Step 2: upsert resilience
    String rid2 = newRequestId();
    UpsertResilienceRequest resReq = new UpsertResilienceRequest(
        5000,
        new CircuitBreaker(50f, 100f, 60000, 10, 100, 60000),
        new BulkHead(25, 0),
        new RateLimiter(100, 1000, 0),
        new Retry(3, 500, Set.of("500","502","503"))
    );
    doPost("/api/outbound/" + code + "/resilience", resReq, rid2).expectStatus().isOk();
    awaitEventLog(rid2);

    // Step 3: upsert authentication (NONE)
    String rid3 = newRequestId();
    UpsertAuthRequest authReq = new UpsertAuthRequest(
        AuthType.NONE, null, null, null, false,
        null, null, null, null, null, null, null, null);
    doPost("/api/outbound/" + code + "/authentication", authReq, rid3).expectStatus().isOk();
    awaitEventLog(rid3);

    // Step 4: upsert headers
    String rid4 = newRequestId();
    UpsertHeadersRequest headersReq = new UpsertHeadersRequest(
        Map.of("X-Source", List.of("perm-test")));
    doPost("/api/outbound/" + code + "/headers", headersReq, rid4).expectStatus().isOk();
    awaitEventLog(rid4);

    // Step 5: execute via gateway
    String rid5 = newRequestId();
    GatewayRequest gwReq = new GatewayRequest(Map.of(), Map.of("amount", 500));
    byte[] gwBody = webTestClient.post()
        .uri("/api/gateway/execute/" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HeaderConstant.REQUEST_ID.getHeader(), rid5)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .bodyValue(gwReq)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();
    JsonNode gwRoot = objectMapper.readTree(gwBody);
    assertThat(gwRoot.path("data").path("status").asInt()).isEqualTo(200);
    awaitEventLog(rid5);

    // Step 6: check histories
    String rid6 = newRequestId();
    byte[] histBody = webTestClient.get()
        .uri("/api/gateway/histories?code=" + code)
        .header(HeaderConstant.REQUEST_ID.getHeader(), rid6)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();
    JsonNode histRoot = objectMapper.readTree(histBody);
    assertThat(histRoot.path("pagination").path("totalElements").asLong()).isPositive();
    awaitEventLog(rid6);

    // Step 7: delete system
    String rid7 = newRequestId();
    webTestClient.delete()
        .uri("/api/outbound/" + code)
        .header(HeaderConstant.REQUEST_ID.getHeader(), rid7)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk();
    assertThat(systemRepository.findByCode(code).block()).isNull();
    awaitEventLog(rid7);

    // Step 8: execute again — should fail with 400
    String rid8 = newRequestId();
    webTestClient.post()
        .uri("/api/gateway/execute/" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HeaderConstant.REQUEST_ID.getHeader(), rid8)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .bodyValue(gwReq)
        .exchange()
        .expectStatus().isBadRequest();
    awaitEventLog(rid8);

    // Total: 8 event_logs
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> eventLogRepository.count().block() >= 8L);
  }

  // --- Permutation 2: Idempotency flow ---

  @Test
  @DisplayName("Permutation 2: idempotency — second call returns fromCache=true")
  void permutation2_idempotencyFlow() throws Exception {
    String code = "perm2-idem";
    String baseUrl = "http://localhost:" + wireMock.port();
    wireMock.stubFor(post(urlEqualTo("/v1/perm2"))
        .willReturn(aResponse()
            .withStatus(200)
            .withBody("{\"idem\":true}")));

    setupSystemInDb(code, baseUrl, "/v1/perm2");

    GatewayRequest gwReq = new GatewayRequest(Map.of(), Map.of("x", 1));

    // Step 1: first call — fromCache=false
    String rid1 = newRequestId();
    byte[] body1 = webTestClient.post()
        .uri("/api/gateway/execute/" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HeaderConstant.REQUEST_ID.getHeader(), rid1)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .header("X-Idempotency-Key", "idem-perm2")
        .bodyValue(gwReq)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();
    JsonNode root1 = objectMapper.readTree(body1);
    assertThat(root1.path("data").path("fromCache").asBoolean()).isFalse();
    awaitEventLog(rid1);

    // Allow async history save
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> historyRepository.count().block() >= 1L);

    // Step 2: second call same idempotency key — fromCache=true
    String rid2 = newRequestId();
    byte[] body2 = webTestClient.post()
        .uri("/api/gateway/execute/" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HeaderConstant.REQUEST_ID.getHeader(), rid2)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .header("X-Idempotency-Key", "idem-perm2")
        .bodyValue(gwReq)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();
    JsonNode root2 = objectMapper.readTree(body2);
    assertThat(root2.path("data").path("fromCache").asBoolean()).isTrue();
    awaitEventLog(rid2);

    // Step 3: histories count = 1
    OutboundSystem system = systemRepository.findByCode(code).block();
    Long histCount = historyRepository.countByFilters(system.getId(), 0, null, null).block();
    assertThat(histCount).isEqualTo(1L);

    // Step 4: metrics idempotencyHits >= 0
    String rid3 = newRequestId();
    byte[] metricsBody = webTestClient.get()
        .uri("/api/outbound/" + code + "/metrics")
        .header(HeaderConstant.REQUEST_ID.getHeader(), rid3)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();
    JsonNode metricsRoot = objectMapper.readTree(metricsBody);
    assertThat(metricsRoot.path("data").path("idempotencyHits").asLong()).isNotNegative();
    awaitEventLog(rid3);

    // Total event_logs: 3
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> eventLogRepository.count().block() >= 3L);
  }

  // --- Permutation 3: Reload flow ---

  @Test
  @DisplayName("Permutation 3: reload — fresh context after eviction produces second history")
  void permutation3_reloadFlow() throws Exception {
    String code = "perm3-reload";
    String baseUrl = "http://localhost:" + wireMock.port();
    wireMock.stubFor(post(urlEqualTo("/v1/perm3"))
        .willReturn(aResponse()
            .withStatus(200)
            .withBody("{\"reload\":true}")));

    setupSystemInDb(code, baseUrl, "/v1/perm3");

    GatewayRequest gwReq = new GatewayRequest(Map.of(), Map.of("step", 1));

    // Step 1: first execute — warm cache
    String rid1 = newRequestId();
    webTestClient.post()
        .uri("/api/gateway/execute/" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HeaderConstant.REQUEST_ID.getHeader(), rid1)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .bodyValue(gwReq)
        .exchange()
        .expectStatus().isOk();
    awaitEventLog(rid1);

    // Step 2: reload
    String rid2 = newRequestId();
    byte[] reloadBody = webTestClient.post()
        .uri("/api/outbound/" + code + "/reload")
        .header(HeaderConstant.REQUEST_ID.getHeader(), rid2)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();
    JsonNode reloadRoot = objectMapper.readTree(reloadBody);
    assertThat(reloadRoot.path("data").asBoolean()).isTrue();
    awaitEventLog(rid2);

    // Allow first execute's history to save
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> historyRepository.count().block() >= 1L);

    // Step 3: second execute — fresh load from DB
    String rid3 = newRequestId();
    webTestClient.post()
        .uri("/api/gateway/execute/" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HeaderConstant.REQUEST_ID.getHeader(), rid3)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .bodyValue(gwReq)
        .exchange()
        .expectStatus().isOk();
    awaitEventLog(rid3);

    // outbound_histories count >= 2
    OutboundSystem system = systemRepository.findByCode(code).block();
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> historyRepository.countByFilters(system.getId(), 0, null, null).block() >= 2L);

    // event_logs count >= 3
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> eventLogRepository.count().block() >= 3L);
  }

  // --- Permutation 4: Upsert authentication then verify execution ---

  @Test
  @DisplayName("Permutation 4: upsert authentication NONE then execute successfully")
  void permutation4_upsertAuthThenExecute() throws Exception {
    String code = "perm4-authentication";
    String baseUrl = "http://localhost:" + wireMock.port();
    wireMock.stubFor(post(urlEqualTo("/v1/perm4"))
        .willReturn(aResponse()
            .withStatus(200)
            .withBody("{\"authentication\":\"ok\"}")));

    // Step 1: create system (host_configuration_id is a mandatory FK — seed it first)
    OutboundHostConfiguration hostConfig = seedOrGetHostConfig(baseUrl);
    String rid1 = newRequestId();
    CreateOutboundSystemRequest createReq = new CreateOutboundSystemRequest(
        code, code + "-name", null, "POST", hostConfig.getId(), "/v1/perm4", true, 24);
    doPost("/api/outbound", createReq, rid1).expectStatus().isOk();
    awaitEventLog(rid1);

    // Step 2: upsert authentication NONE
    String rid2 = newRequestId();
    UpsertAuthRequest authReq = new UpsertAuthRequest(
        AuthType.NONE, null, null, null, false,
        null, null, null, null, null, null, null, null);
    doPost("/api/outbound/" + code + "/authentication", authReq, rid2).expectStatus().isOk();
    awaitEventLog(rid2);

    // Seed resilience for gateway (host config already seeded before create above)
    OutboundSystem system = systemRepository.findByCode(code).block();
    resilienceRepository.save(OutboundResilienceConfiguration.builder()
        .outboundSystemId(system.getId())
        .readTimeOutMs(5000)
        .circuitBreakerEnabled(false)
        .bulkheadEnabled(false)
        .rateLimiterEnabled(false)
        .retryEnabled(false)
        .build()).block();

    // Step 3: execute
    String rid3 = newRequestId();
    GatewayRequest gwReq = new GatewayRequest(Map.of(), Map.of("test", true));
    byte[] gwBody = webTestClient.post()
        .uri("/api/gateway/execute/" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HeaderConstant.REQUEST_ID.getHeader(), rid3)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .bodyValue(gwReq)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();
    JsonNode gwRoot = objectMapper.readTree(gwBody);
    assertThat(gwRoot.path("data").path("status").asInt()).isEqualTo(200);
    awaitEventLog(rid3);

    // All 3 steps produce event_logs
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> eventLogRepository.count().block() >= 3L);
  }

  // --- Permutation 5: Metrics after seeded calls ---

  @Test
  @DisplayName("Permutation 5: metrics — seeded histories return correct p90/p99")
  void permutation5_metricsAfterCalls() throws Exception {
    String code = "perm5-metrics";

    OutboundHostConfiguration hostConfig = seedOrGetHostConfig("http://localhost:9999");
    OutboundSystem system = systemRepository.save(OutboundSystem.builder()
        .code(code)
        .name(code + "-name")
        .httpMethod("POST")
        .hostConfigurationId(hostConfig.getId())
        .apiPath("/v1/perm5")
        .active(true)
        .idempotencyTtlHours(24)
        .build()).block();

    // Seed 5 histories with known durations
    int[] durations = {100, 200, 300, 400, 500};
    for (int d : durations) {
      historyRepository.save(OutboundHistory.builder()
          .outboundSystemId(system.getId())
          .requestMethod("POST")
          .requestUrl("http://localhost:9999/v1/perm5")
          .responseStatus(200)
          .durationMs(d)
          .build()).block();
    }

    String rid1 = newRequestId();
    byte[] body = webTestClient.get()
        .uri("/api/outbound/" + code + "/metrics")
        .header(HeaderConstant.REQUEST_ID.getHeader(), rid1)
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
    assertThat(root.path("data").path("totalCalls").asLong()).isEqualTo(5L);
    assertThat(root.path("data").path("avgDurationMs").asLong()).isEqualTo(300L);
    assertThat(root.path("data").path("p90DurationMs").asLong()).isGreaterThanOrEqualTo(400L);
    assertThat(root.path("data").path("p99DurationMs").asLong()).isGreaterThanOrEqualTo(490L);
    awaitEventLog(rid1);

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> eventLogRepository.count().block() >= 1L);
  }

  // --- Permutation 6: multi-call via execute API → admin metrics p-values ---

  @Test
  @DisplayName("Permutation 6: 5 live execute calls — admin metrics reflects correct totalCalls and p-values")
  void permutation6_metricsAfterLiveExecuteCalls() throws Exception {
    String code = "perm6-metrics";

    wireMock.stubFor(post(urlEqualTo("/v1/perm6"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    setupSystemInDb(code, baseUrl, "/v1/perm6");

    GatewayRequest req = new GatewayRequest(Map.of(), Map.of("amount", 100));

    // 5 live execute calls
    for (int i = 0; i < 5; i++) {
      doPost("/api/gateway/execute/" + code, req, newRequestId()).expectStatus().isOk();
    }

    // Allow async history saves to complete
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .until(() -> historyRepository.countByFilters(0, 0, null, null).block() >= 5L);

    String metricsRid = newRequestId();
    byte[] body = webTestClient.get()
        .uri("/api/outbound/" + code + "/metrics")
        .header(HeaderConstant.REQUEST_ID.getHeader(), metricsRid)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");

    JsonNode data = root.path("data");
    assertThat(data.path("totalCalls").asLong()).isEqualTo(5L);
    assertThat(data.path("avgDurationMs").asLong()).isNotNegative();
    assertThat(data.path("p90DurationMs").asLong())
        .isGreaterThanOrEqualTo(data.path("avgDurationMs").asLong());
    assertThat(data.path("p99DurationMs").asLong())
        .isGreaterThanOrEqualTo(data.path("p90DurationMs").asLong());

    awaitEventLog(metricsRid);
  }

  // --- Helpers ---

  private void setupSystemInDb(String code, String baseUrl, String apiPath) {
    OutboundHostConfiguration hostConfig = seedOrGetHostConfig(baseUrl);
    OutboundSystem system = systemRepository.save(OutboundSystem.builder()
        .code(code)
        .name(code + "-name")
        .httpMethod("POST")
        .hostConfigurationId(hostConfig.getId())
        .apiPath(apiPath)
        .active(true)
        .idempotencyTtlHours(24)
        .build()).block();

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
  }

  /** host_configuration_id is a mandatory FK — reuse an existing row for {@code baseUrl} or create one. */
  private OutboundHostConfiguration seedOrGetHostConfig(String baseUrl) {
    OutboundHostConfiguration existing = hostConfigRepository.findByBaseUrl(baseUrl).block();
    if (existing != null) {
      return existing;
    }
    return hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl(baseUrl)
        .connectTimeOutMs(5000)
        .maxConnections(50)
        .pendingAcquireMaxCount(100)
        .pendingAcquireTimeoutMs(5000)
        .disableSslVerification(false)
        .useProxy(false)
        .build()).block();
  }

  private String newRequestId() {
    return TsidHelper.generateStringId();
  }

  private WebTestClient.ResponseSpec doPost(String uri, Object body, String requestId) {
    return webTestClient.post()
        .uri(uri)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HeaderConstant.REQUEST_ID.getHeader(), requestId)
        .header(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .header(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .bodyValue(body)
        .exchange();
  }

  private void awaitEventLog(String requestId) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .until(() -> eventLogRepository.findByRequestId(requestId).block() != null);
  }
}
