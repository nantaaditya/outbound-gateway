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
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.request.admin.CreateHostConfigRequest;
import com.nantaaditya.example.model.request.admin.CreateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpsertAuthRequest;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest.BulkHead;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest.CircuitBreaker;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest.RateLimiter;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest.Retry;
import com.nantaaditya.example.model.request.admin.UpsertResponseMapperRequest;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResponseMapperConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Sequential, stateful full-lifecycle integration test for a single outbound system.
 *
 * <h3>Scope</h3>
 * <ol>
 *   <li>Create host config → create outbound system → one {@code /test} smoke check.</li>
 *   <li>Resilience alone: 5 variants (CB/RL/BH/Retry-only, all-4-combined), each immediately
 *       verified via {@code /api/gateway/execute} (success / failed / idempotency).</li>
 *   <li>Resilience × auth, full cross-product: 5 × 7 = 35 combinations, same verification triple.</li>
 *   <li>Resilience × auth × response-mapper, full cross-product: 5 × 7 × 2 = 70 combinations,
 *       same verification triple.</li>
 *   <li>Metrics and histories reflect the accumulated totals from the whole run.</li>
 * </ol>
 *
 * <p>"Test connection" here means {@code POST /api/gateway/execute/{code}} — the only endpoint
 * where resilience, auth, and response-mapper configuration actually take effect and the only
 * one with idempotency semantics. The admin {@code /test} endpoint bypasses all of that (see
 * {@code OutboundServiceImpl.testConnection}), so it is only exercised once as a smoke check.
 *
 * <p>"Failed" means the upstream returns a persistent 500. Non-retry resilience variants forward
 * that 500 unchanged (CB/RL/BH alone don't react to a single call's status code — they gate on
 * concurrency/rate/circuit-state, not response content). Retry-enabled variants configure
 * {@code retryOnStatusCodes} to include 500, exhaust their attempts against the always-500 stub,
 * and the final forwarded status is 502 (matching the established behavior in
 * {@code GatewayExecuteResilienceIntegrationTest}).
 *
 * <p>No {@code @AfterEach} cleanup — state must accumulate across the whole ordered run. Cleanup
 * happens once in {@link #cleanupAll()}.
 */
@TestInstance(Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class GatewaySequentialLifecycleIntegrationTest extends BaseIntegrationTest {

  private static final String CODE = "seq-lifecycle-test";
  private static final String API_PATH = "/v1/seq-lifecycle";
  private static final String TOKEN_PATH = "/oauth/seq-token";
  private static final String JSLT_SCRIPT = "{\"id\": .txId, \"total\": .amount}";

  private static final UpsertAuthRequest AUTH_NONE =
      new UpsertAuthRequest(AuthType.NONE, null, null, null, false, null, null, null, null, null, null, null, null);
  private static final UpsertResponseMapperRequest MAPPER_NONE =
      new UpsertResponseMapperRequest(ConverterType.NONE, null, null, true);

  static WireMockServer wireMock;
  static KeyPair rsaKeyPair;
  static String baseUrl;

  @BeforeAll
  static void startWireMock() throws Exception {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
    baseUrl = "http://localhost:" + wireMock.port();

    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    rsaKeyPair = gen.generateKeyPair();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @Autowired private OutboundHostConfigurationRepository hostConfigRepository;
  @Autowired private OutboundSystemRepository systemRepository;
  @Autowired private OutboundResilienceConfigurationRepository resilienceRepository;
  @Autowired private OutboundAuthConfigurationRepository authRepository;
  @Autowired private OutboundResponseMapperConfigurationRepository mapperRepository;
  @Autowired private OutboundHistoryRepository historyRepository;
  @Autowired private ObjectMapper objectMapper;

  private long hostConfigId;
  private long systemId;
  private long expectedHistoryRows = 0;
  private int idemCounter = 0;

  @AfterAll
  void cleanupAll() {
    historyRepository.deleteAll().block();
    mapperRepository.deleteAll().block();
    authRepository.deleteAll().block();
    resilienceRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
  }

  // ============================================================
  // Phase 1 — setup
  // ============================================================

  @Test
  @Order(1)
  @DisplayName("Step 1: create outbound host")
  void step1_createHost() throws Exception {
    CreateHostConfigRequest req = new CreateHostConfigRequest(
        baseUrl, 5000, 100, 200, 5000, false, false, null, 0, null, null);

    byte[] body = doPost("/api/outbound/host", req)
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    hostConfigId = root.path("data").path("id").asLong();
    assertThat(hostConfigId).isPositive();
  }

  @Test
  @Order(2)
  @DisplayName("Step 2: create outbound system")
  void step2_createSystem() throws Exception {
    CreateOutboundSystemRequest req = new CreateOutboundSystemRequest(
        CODE, "Sequential Lifecycle Test", "full lifecycle cross-product scenario",
        "POST", hostConfigId, API_PATH, true, 24);

    doPost("/api/outbound", req).expectStatus().isOk();

    OutboundSystem system = systemRepository.findByCode(CODE).block();
    assertThat(system).isNotNull();
    systemId = system.getId();

    // Registered once, up front — harmless when a given combination's auth type isn't OAUTH.
    stubOAuthToken();
  }

  @Test
  @Order(3)
  @DisplayName("Step 3: /test smoke check (admin connectivity endpoint, bypasses resilience/auth/mapper)")
  void step3_testConnectionSmoke() throws Exception {
    stubMainApi(200);

    byte[] body = webTestClient.post()
        .uri("/api/outbound/" + CODE + "/test")
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("data").path("success").asBoolean()).isTrue();
    assertThat(root.path("data").path("httpStatus").asInt()).isEqualTo(200);
  }

  // ============================================================
  // Phase 2 — resilience alone (5 variants), each immediately verified
  // ============================================================

  @ParameterizedTest(name = "[{index}] resilience={0}")
  @MethodSource("resilienceVariantsSource")
  @Order(10)
  @DisplayName("Step 4: upsert resilience variant, then verify via gateway execute")
  void step4_resilienceAlone(ResilienceVariant rv) throws Exception {
    upsertResilience(rv.request());
    upsertAuth(AUTH_NONE);
    upsertMapper(MAPPER_NONE);

    runTriple(rv.expectedFailedStatus());
  }

  // ============================================================
  // Phase 3 — resilience x auth, full cross-product (5 x 7 = 35), each immediately verified
  // ============================================================

  @ParameterizedTest(name = "[{index}] resilience={0} auth={1}")
  @MethodSource("resilienceAuthCombosSource")
  @Order(20)
  @DisplayName("Step 5: upsert resilience + auth combination, then verify via gateway execute")
  void step5_resilienceAuthCross(ResilienceVariant rv, AuthVariant av) throws Exception {
    upsertResilience(rv.request());
    upsertAuth(av.request());
    upsertMapper(MAPPER_NONE);

    runTriple(rv.expectedFailedStatus());
  }

  // ============================================================
  // Phase 4 — resilience x auth x mapper, full cross-product (5 x 7 x 2 = 70), each verified
  // ============================================================

  @ParameterizedTest(name = "[{index}] resilience={0} auth={1} mapper={2}")
  @MethodSource("resilienceAuthMapperCombosSource")
  @Order(30)
  @DisplayName("Step 6: upsert resilience + auth + mapper combination, then verify via gateway execute")
  void step6_resilienceAuthMapperCross(ResilienceVariant rv, AuthVariant av, MapperVariant mv) throws Exception {
    upsertResilience(rv.request());
    upsertAuth(av.request());
    upsertMapper(mv.request());

    runTriple(rv.expectedFailedStatus());
  }

  // ============================================================
  // Phase 5 — metrics & histories for the whole accumulated scenario
  // ============================================================

  @Test
  @Order(40)
  @DisplayName("Step 7: metrics and histories reflect the full scenario")
  void step7_metricsAndHistories() throws Exception {
    byte[] metricsBody = webTestClient.get()
        .uri("/api/outbound/" + CODE + "/metrics")
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();

    JsonNode metrics = objectMapper.readTree(metricsBody);
    assertThat(metrics.path("data").path("totalCalls").asLong()).isGreaterThanOrEqualTo(expectedHistoryRows);
    assertThat(metrics.path("data").path("idempotencyHits").asLong()).isPositive();

    byte[] histBody = webTestClient.get()
        .uri("/api/gateway/histories?code=" + CODE + "&size=1")
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();

    JsonNode histRoot = objectMapper.readTree(histBody);
    assertThat(histRoot.path("pagination").path("totalElements").asLong()).isGreaterThanOrEqualTo(expectedHistoryRows);
  }

  // ============================================================
  // Combination sources
  // ============================================================

  Stream<ResilienceVariant> resilienceVariantsSource() {
    return resilienceVariants().stream();
  }

  Stream<Arguments> resilienceAuthCombosSource() {
    return resilienceVariants().stream()
        .flatMap(rv -> authVariants().stream().map(av -> Arguments.of(rv, av)));
  }

  Stream<Arguments> resilienceAuthMapperCombosSource() {
    return resilienceVariants().stream()
        .flatMap(rv -> authVariants().stream()
            .flatMap(av -> mapperVariants().stream().map(mv -> Arguments.of(rv, av, mv))));
  }

  private List<ResilienceVariant> resilienceVariants() {
    return List.of(
        new ResilienceVariant("CB_ONLY", new UpsertResilienceRequest(
            5000,
            new CircuitBreaker(50f, 100f, 60000, 2, 10, 1000),
            null, null, null
        ), 500),
        new ResilienceVariant("RL_ONLY", new UpsertResilienceRequest(
            5000, null, null,
            new RateLimiter(100, 1000, 0),
            null
        ), 500),
        new ResilienceVariant("BH_ONLY", new UpsertResilienceRequest(
            5000, null,
            new BulkHead(25, 0),
            null, null
        ), 500),
        new ResilienceVariant("RETRY_ONLY", new UpsertResilienceRequest(
            5000, null, null, null,
            new Retry(3, 1, Set.of("500", "502", "503"))
        ), 502),
        new ResilienceVariant("COMBINED", new UpsertResilienceRequest(
            5000,
            new CircuitBreaker(50f, 100f, 60000, 2, 10, 1000),
            new BulkHead(25, 0),
            new RateLimiter(100, 1000, 0),
            new Retry(3, 1, Set.of("500", "502", "503"))
        ), 502)
    );
  }

  private List<AuthVariant> authVariants() {
    return List.of(
        new AuthVariant("NONE", AUTH_NONE),
        new AuthVariant("API_KEY", new UpsertAuthRequest(
            AuthType.API_KEY, "seq-api-key", null, null, false,
            null, null, null, null, TokenInjectLocation.HEADER, "X-API-Key", null, null)),
        new AuthVariant("BASIC", new UpsertAuthRequest(
            AuthType.BASIC, null, "seq-user", "seq-pass", false,
            null, null, null, null, null, null, null, null)),
        new AuthVariant("BEARER_STATIC", new UpsertAuthRequest(
            AuthType.BEARER_STATIC, null, null, "seq-static-token", false,
            null, null, null, "Bearer", TokenInjectLocation.HEADER, "Authorization", null, null)),
        new AuthVariant("OAUTH", new UpsertAuthRequest(
            AuthType.OAUTH, null, "seq-oauth-client", "seq-oauth-secret", true,
            "POST", baseUrl + TOKEN_PATH, null, "Bearer", TokenInjectLocation.HEADER,
            "Authorization", "access_token", "expires_in")),
        new AuthVariant("HMAC", new UpsertAuthRequest(
            AuthType.HMAC, null, null, "seq-hmac-secret", false,
            null, null, null, null, TokenInjectLocation.HEADER, "X-Signature", null, null)),
        new AuthVariant("RSA", new UpsertAuthRequest(
            // clientId is functionally unused by RsaAuthStrategy but required by
            // UpsertAuthRequest.isAuthFieldsValid() for RSA — any non-blank value satisfies it.
            AuthType.RSA, null, "seq-rsa-key-id",
            Base64.getEncoder().encodeToString(rsaKeyPair.getPrivate().getEncoded()), false,
            null, null, null, null, TokenInjectLocation.HEADER, "X-RSA-Signature", null, null))
    );
  }

  private List<MapperVariant> mapperVariants() {
    return List.of(
        new MapperVariant("NONE", MAPPER_NONE),
        new MapperVariant("JSLT", new UpsertResponseMapperRequest(ConverterType.JSLT, JSLT_SCRIPT, null, true))
    );
  }

  // ============================================================
  // Records
  // ============================================================

  record ResilienceVariant(String label, UpsertResilienceRequest request, int expectedFailedStatus) {
    @Override
    public String toString() {
      return label;
    }
  }

  record AuthVariant(String label, UpsertAuthRequest request) {
    @Override
    public String toString() {
      return label;
    }
  }

  record MapperVariant(String label, UpsertResponseMapperRequest request) {
    @Override
    public String toString() {
      return label;
    }
  }

  // ============================================================
  // Helpers
  // ============================================================

  private void runTriple(int expectedFailedStatus) throws Exception {
    checkSuccess();
    checkFailed(expectedFailedStatus);
    checkIdempotency();
  }

  private void checkSuccess() throws Exception {
    stubMainApi(200);
    JsonNode root = executeGateway(null);
    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    awaitHistoryCount(expectedHistoryRows + 1);
    expectedHistoryRows++;
  }

  private void checkFailed(int expectedStatus) throws Exception {
    stubMainApi(500);
    JsonNode root = executeGateway(null);
    assertThat(root.path("data").path("status").asInt()).isEqualTo(expectedStatus);
    awaitHistoryCount(expectedHistoryRows + 1);
    expectedHistoryRows++;
  }

  private void checkIdempotency() throws Exception {
    stubMainApi(200);
    String key = "seq-idem-" + (idemCounter++);

    JsonNode first = executeGateway(key);
    assertThat(first.path("data").path("fromCache").asBoolean()).isFalse();
    awaitHistoryCount(expectedHistoryRows + 1);
    expectedHistoryRows++;

    JsonNode second = executeGateway(key);
    assertThat(second.path("data").path("fromCache").asBoolean()).isTrue();
    // cache hit — no new history row, expectedHistoryRows unchanged
  }

  private void upsertResilience(UpsertResilienceRequest req) {
    doPost("/api/outbound/" + CODE + "/resilience", req).expectStatus().isOk();
  }

  private void upsertAuth(UpsertAuthRequest req) {
    doPost("/api/outbound/" + CODE + "/authentication", req).expectStatus().isOk();
  }

  private void upsertMapper(UpsertResponseMapperRequest req) {
    doPost("/api/outbound/" + CODE + "/response-mapper", req).expectStatus().isOk();
  }

  private void stubMainApi(int status) {
    wireMock.stubFor(post(urlEqualTo(API_PATH))
        .willReturn(aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody(status < 300 ? "{\"amount\":100,\"txId\":\"seq-tx\"}" : "{\"error\":\"upstream failed\"}")));
  }

  private void stubOAuthToken() {
    wireMock.stubFor(post(urlEqualTo(TOKEN_PATH))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"access_token\":\"seq-oauth-token\",\"expires_in\":3600}")));
  }

  private JsonNode executeGateway(String idempotencyKey) throws Exception {
    GatewayRequest req = new GatewayRequest(Map.of(), Map.of("amount", 100));
    WebTestClient.RequestBodySpec spec = webTestClient.post()
        .uri("/api/gateway/execute/" + CODE)
        .contentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      spec.header(ConstantName.IDEMPOTENT_HEADER, idempotencyKey);
    }
    byte[] body = spec.bodyValue(req)
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();
    return objectMapper.readTree(body);
  }

  private void awaitHistoryCount(long expected) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(3))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> historyRepository.countByFilters(systemId, 0, null, null).block() >= expected);
  }

  private WebTestClient.ResponseSpec doPost(String uri, Object body) {
    return webTestClient.post()
        .uri(uri)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }
}
