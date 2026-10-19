package com.nantaaditya.example.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundParameter;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ParameterInjectLocation;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.request.admin.UpsertParametersRequest;
import com.nantaaditya.example.model.request.admin.UpsertParametersRequest.Item;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundParameterRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;

class OutboundParameterIntegrationTest extends BaseIntegrationTest {

  private static final String PATH = "/v1/param-target";
  // Unique code per test: GatewayServiceImpl caches the loaded context by code across tests.
  private static final AtomicInteger CODE_SEQ = new AtomicInteger();
  private String code;

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

  @Autowired private OutboundSystemRepository systemRepository;
  @Autowired private OutboundHostConfigurationRepository hostConfigRepository;
  @Autowired private OutboundResilienceConfigurationRepository resilienceRepository;
  @Autowired private OutboundAuthConfigurationRepository authRepository;
  @Autowired private OutboundParameterRepository parameterRepository;
  @Autowired private OutboundHistoryRepository historyRepository;
  @Autowired private EncryptionHelper encryptionHelper;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void newCode() {
    code = "param-target-" + CODE_SEQ.incrementAndGet();
  }

  @AfterEach
  void cleanup() {
    parameterRepository.deleteAll().block();
    authRepository.deleteAll().block();
    resilienceRepository.deleteAll().block();
    historyRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    wireMock.resetAll();
  }

  // --- gateway: injection ---

  @Test
  @DisplayName("HEADER parameter — client key injects the stored value into the configured header")
  void execute_headerParameter_injectsHeader() throws Exception {
    stubOk();
    OutboundSystem system = seedSystem();
    seedParameter(system, "A", "B", ParameterInjectLocation.HEADER, "X-Partner-Id", true);

    JsonNode root = executeGateway(Map.of("amount", 100), List.of("A"), 200);

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    wireMock.verify(postRequestedFor(urlPathEqualTo(PATH)).withHeader("X-Partner-Id", equalTo("B")));
  }

  @Test
  @DisplayName("QUERY_PARAM parameter — injects the stored value into the outbound query string")
  void execute_queryParameter_injectsQuery() throws Exception {
    stubOk();
    OutboundSystem system = seedSystem();
    seedParameter(system, "A", "B", ParameterInjectLocation.QUERY_PARAM, "partner_id", true);

    executeGateway(Map.of("amount", 100), List.of("A"), 200);

    wireMock.verify(postRequestedFor(urlPathEqualTo(PATH)).withQueryParam("partner_id", equalTo("B")));
  }

  @Test
  @DisplayName("BODY parameter — injects at the dot path; history keeps the original payload without the secret")
  void execute_bodyParameter_injectsBodyAndHistoryKeepsOriginal() throws Exception {
    stubOk();
    OutboundSystem system = seedSystem();
    seedParameter(system, "A", "secret-B", ParameterInjectLocation.BODY, "meta.partnerId", true);

    executeGateway(Map.of("amount", 100), List.of("A"), 200);

    JsonNode sent = objectMapper.readTree(findLoggedRequest().getBodyAsString());
    assertThat(sent.at("/meta/partnerId").asText()).isEqualTo("secret-B");
    assertThat(sent.get("amount").asInt()).isEqualTo(100);

    Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> historyRepository.count().block() >= 1);
    OutboundHistory history = historyRepository.findAll().blockFirst();
    assertThat(history).isNotNull();
    assertThat(history.getRequestPayload()).doesNotContain("secret-B").contains("amount");
  }

  @Test
  @DisplayName("HMAC auth — the signature covers the body including the injected BODY parameter")
  void execute_bodyParameterWithHmac_signatureCoversInjectedBody() throws Exception {
    stubOk();
    OutboundSystem system = seedSystem();
    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.HMAC)
        .clientSecret(encryptionHelper.encryptString("hmac-secret"))
        .tokenKeyName("X-Signature")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build()).block();
    seedParameter(system, "A", "B", ParameterInjectLocation.BODY, "partnerId", true);

    executeGateway(Map.of("amount", 100), List.of("A"), 200);

    LoggedRequest received = findLoggedRequest();
    assertThat(received.getBodyAsString()).contains("partnerId");
    assertThat(received.getHeader("X-Signature"))
        .isEqualTo(hmac("hmac-secret", received.getBodyAsString()));
  }

  @Test
  @DisplayName("without injectKeys nothing is injected even when parameters exist")
  void execute_noInjectKeys_injectsNothing() throws Exception {
    stubOk();
    OutboundSystem system = seedSystem();
    seedParameter(system, "A", "B", ParameterInjectLocation.HEADER, "X-Partner-Id", true);

    executeGateway(Map.of("amount", 100), null, 200);

    wireMock.verify(postRequestedFor(urlPathEqualTo(PATH)).withoutHeader("X-Partner-Id"));
  }

  // --- gateway: rejections (no outbound call) ---

  @Test
  @DisplayName("unknown key — 400 and no outbound call")
  void execute_unknownKey_returns400() throws Exception {
    stubOk();
    seedSystem();

    executeGateway(Map.of("amount", 100), List.of("MISSING"), 400);

    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }

  @Test
  @DisplayName("inactive key — 400 and no outbound call")
  void execute_inactiveKey_returns400() throws Exception {
    stubOk();
    OutboundSystem system = seedSystem();
    seedParameter(system, "A", "B", ParameterInjectLocation.HEADER, "X-Partner-Id", false);

    executeGateway(Map.of("amount", 100), List.of("A"), 400);

    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }

  @Test
  @DisplayName("key belonging to another host — 400 (parameters are host-scoped)")
  void execute_keyOfAnotherHost_returns400() throws Exception {
    stubOk();
    seedSystem();
    OutboundHostConfiguration other = hostConfigRepository.save(host("http://other-host.example.com")).block();
    parameterRepository.save(OutboundParameter.create(other.getId(), "A",
        encryptionHelper.encryptString("B"), ParameterInjectLocation.HEADER, "X-Partner-Id", true)).block();

    executeGateway(Map.of("amount", 100), List.of("A"), 400);

    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }

  @Test
  @DisplayName("BODY parameter without a request body — 400")
  void execute_bodyParameterWithoutBody_returns400() throws Exception {
    stubOk();
    OutboundSystem system = seedSystem();
    seedParameter(system, "A", "B", ParameterInjectLocation.BODY, "partnerId", true);

    executeGateway(null, List.of("A"), 400);

    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }

  @Test
  @DisplayName("parameter targeting the auth key name — 400, auth header cannot be overridden")
  void execute_parameterTargetsAuthKey_returns400() throws Exception {
    stubOk();
    OutboundSystem system = seedSystem();
    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.API_KEY)
        .apiKey(encryptionHelper.encryptString("real-key"))
        .tokenKeyName("X-API-Key")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build()).block();
    seedParameter(system, "A", "evil", ParameterInjectLocation.HEADER, "x-api-key", true);

    executeGateway(Map.of("amount", 100), List.of("A"), 400);

    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }

  @Test
  @DisplayName("QUERY_PARAM value with reserved characters is sent as one encoded value (no template expansion / param smuggling)")
  void execute_queryParameterWithSpecialChars_isEncodedAsSingleValue() throws Exception {
    stubOk();
    OutboundSystem system = seedSystem();
    seedParameter(system, "A", "a&admin=true {x}+b", ParameterInjectLocation.QUERY_PARAM, "token", true);

    JsonNode root = executeGateway(Map.of("amount", 100), List.of("A"), 200);

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    LoggedRequest received = findLoggedRequest();
    assertThat(received.queryParameter("token").firstValue()).isEqualTo("a&admin=true {x}+b");
    assertThat(received.queryParameter("admin").isPresent()).isFalse();
  }

  @Test
  @DisplayName("BODY parameter with an array body — 400 (not an internal reject), no outbound call")
  void execute_bodyParameterWithArrayBody_returns400() throws Exception {
    stubOk();
    OutboundSystem system = seedSystem();
    seedParameter(system, "A", "B", ParameterInjectLocation.BODY, "partnerId", true);

    executeGateway(List.of(1, 2), List.of("A"), 400);

    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }

  @Test
  @DisplayName("injectKeys above the limit — 400 before any lookup")
  void execute_tooManyInjectKeys_returns400() throws Exception {
    stubOk();
    seedSystem();
    List<String> keys = java.util.stream.IntStream.range(0, 51).mapToObj(i -> "k" + i).toList();

    executeGateway(Map.of("amount", 100), keys, 400);

    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }

  // --- admin API ---

  @Test
  @DisplayName("POST /host/{id}/parameters — stores encrypted value, response never contains the value")
  void upsert_validRequest_storesEncryptedAndHidesValue() throws Exception {
    OutboundHostConfiguration host = hostConfigRepository.save(host("http://admin-host.example.com")).block();

    JsonNode root = upsert(host.getId(), item("A", "plain-secret", ParameterInjectLocation.HEADER, "X-Partner-Id"), 200);

    assertThat(root.path("data").get(0).path("key").asText()).isEqualTo("A");
    assertThat(root.toString()).doesNotContain("plain-secret");
    OutboundParameter saved = parameterRepository
        .findByHostConfigurationIdAndParamKey(host.getId(), "A").block();
    assertThat(saved).isNotNull();
    assertThat(new String(saved.getParamValue(), StandardCharsets.UTF_8)).doesNotContain("plain-secret");
    assertThat(encryptionHelper.decryptToString(saved.getParamValue())).isEqualTo("plain-secret");
  }

  @Test
  @DisplayName("POST /host/{id}/parameters — existing key is updated in place, not duplicated")
  void upsert_existingKey_updatesInPlace() throws Exception {
    OutboundHostConfiguration host = hostConfigRepository.save(host("http://admin-host2.example.com")).block();
    upsert(host.getId(), item("A", "v1", ParameterInjectLocation.HEADER, "X-One"), 200);

    upsert(host.getId(), item("A", "v2", ParameterInjectLocation.QUERY_PARAM, "two"), 200);

    List<OutboundParameter> all = parameterRepository.findByHostConfigurationId(host.getId()).collectList().block();
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getInjectLocation()).isEqualTo(ParameterInjectLocation.QUERY_PARAM);
    assertThat(encryptionHelper.decryptToString(all.get(0).getParamValue())).isEqualTo("v2");
  }

  @Test
  @DisplayName("POST /host/{id}/parameters — protected header, bad path, duplicate key, unknown host are 400")
  void upsert_invalidRequests_return400() throws Exception {
    OutboundHostConfiguration host = hostConfigRepository.save(host("http://admin-host3.example.com")).block();

    upsert(host.getId(), item("A", "v", ParameterInjectLocation.HEADER, "Authorization"), 400);
    upsert(host.getId(), item("A", "v", ParameterInjectLocation.BODY, "a..b"), 400);
    upsert(host.getId(), List.of(
        item("A", "v", ParameterInjectLocation.HEADER, "X-One").parameters().get(0),
        item("A", "v", ParameterInjectLocation.HEADER, "X-Two").parameters().get(0)), 400);
    upsert(host.getId(), item("A", "v\r\nX-Evil: 1", ParameterInjectLocation.HEADER, "X-One"), 400);
    upsert(999_999L, item("A", "v", ParameterInjectLocation.HEADER, "X-One"), 400);
    assertThat(parameterRepository.count().block()).isZero();
  }

  @Test
  @DisplayName("GET /host/{id}/parameters — lists keys without values")
  void list_returnsKeysWithoutValues() throws Exception {
    OutboundHostConfiguration host = hostConfigRepository.save(host("http://admin-host4.example.com")).block();
    upsert(host.getId(), item("A", "top-secret", ParameterInjectLocation.HEADER, "X-One"), 200);

    byte[] body = webTestClient.get().uri("/api/outbound/host/" + host.getId() + "/parameters")
        .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("data")).hasSize(1);
    assertThat(root.toString()).doesNotContain("top-secret");
  }

  @Test
  @DisplayName("DELETE /host/{id}/parameters/{key} — removes the key; unknown key is 400")
  void delete_existingAndUnknownKey() throws Exception {
    OutboundHostConfiguration host = hostConfigRepository.save(host("http://admin-host5.example.com")).block();
    upsert(host.getId(), item("A", "v", ParameterInjectLocation.HEADER, "X-One"), 200);

    webTestClient.delete().uri("/api/outbound/host/" + host.getId() + "/parameters/A")
        .exchange().expectStatus().isOk();
    webTestClient.delete().uri("/api/outbound/host/" + host.getId() + "/parameters/A")
        .exchange().expectStatus().isBadRequest();

    assertThat(parameterRepository.count().block()).isZero();
  }

  @Test
  @DisplayName("parameter changed via admin API applies to the very next gateway call")
  void upsert_thenExecute_nextCallSeesNewValue() throws Exception {
    stubOk();
    OutboundSystem system = seedSystem();
    Long hostId = system.getHostConfigurationId();
    upsert(hostId, item("A", "old", ParameterInjectLocation.HEADER, "X-Partner-Id"), 200);
    executeGateway(Map.of("amount", 1), List.of("A"), 200);

    upsert(hostId, item("A", "new", ParameterInjectLocation.HEADER, "X-Partner-Id"), 200);
    executeGateway(Map.of("amount", 1), List.of("A"), 200);

    wireMock.verify(1, postRequestedFor(urlPathEqualTo(PATH)).withHeader("X-Partner-Id", equalTo("old")));
    wireMock.verify(1, postRequestedFor(urlPathEqualTo(PATH)).withHeader("X-Partner-Id", equalTo("new")));
  }

  // --- helpers ---

  private void stubOk() {
    wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
  }

  private OutboundHostConfiguration host(String baseUrl) {
    return OutboundHostConfiguration.builder()
        .baseUrl(baseUrl).connectTimeOutMs(5000).maxConnections(50)
        .pendingAcquireMaxCount(100).pendingAcquireTimeoutMs(5000)
        .disableSslVerification(false).useProxy(false).build();
  }

  private OutboundSystem seedSystem() {
    OutboundHostConfiguration hostConfig = hostConfigRepository
        .save(host("http://localhost:" + wireMock.port())).block();
    OutboundSystem system = systemRepository.save(OutboundSystem.builder()
        .code(code).name(code).httpMethod("POST").hostConfigurationId(hostConfig.getId()).apiPath(PATH)
        .active(true).idempotencyTtlHours(24).build()).block();
    resilienceRepository.save(OutboundResilienceConfiguration.builder()
        .outboundSystemId(system.getId()).readTimeOutMs(10000)
        .circuitBreakerEnabled(false).bulkheadEnabled(false)
        .rateLimiterEnabled(false).retryEnabled(false).build()).block();
    return system;
  }

  private void seedParameter(OutboundSystem system, String key, String value,
      ParameterInjectLocation location, String target, boolean active) {
    parameterRepository.save(OutboundParameter.create(system.getHostConfigurationId(), key,
        encryptionHelper.encryptString(value), location, target, active)).block();
  }

  private JsonNode executeGateway(Object body, List<String> injectKeys, int expectedStatus) throws Exception {
    GatewayRequest req = new GatewayRequest(Map.of(), body, injectKeys);
    byte[] response = webTestClient.post()
        .uri("/api/gateway/execute/" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(req)
        .exchange()
        .expectStatus().isEqualTo(expectedStatus)
        .expectBody().returnResult().getResponseBody();
    return objectMapper.readTree(response);
  }

  private static UpsertParametersRequest item(String key, String value,
      ParameterInjectLocation location, String target) {
    return new UpsertParametersRequest(List.of(new Item(key, value, location, target, null)));
  }

  private JsonNode upsert(long hostId, UpsertParametersRequest request, int expectedStatus) throws Exception {
    return upsert(hostId, request.parameters(), expectedStatus);
  }

  private JsonNode upsert(long hostId, List<Item> items, int expectedStatus) throws Exception {
    ResponseSpec spec = webTestClient.post()
        .uri("/api/outbound/host/" + hostId + "/parameters")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new UpsertParametersRequest(items))
        .exchange()
        .expectStatus().isEqualTo(expectedStatus);
    return objectMapper.readTree(spec.expectBody().returnResult().getResponseBody());
  }

  private LoggedRequest findLoggedRequest() {
    return wireMock.findAll(postRequestedFor(urlPathEqualTo(PATH))).stream().findFirst()
        .orElseThrow(() -> new AssertionError("No request recorded for " + PATH));
  }

  private String hmac(String secret, String data) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
  }
}
