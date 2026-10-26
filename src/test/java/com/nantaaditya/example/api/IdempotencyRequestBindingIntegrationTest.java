package com.nantaaditya.example.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.request.admin.CreateHostConfigRequest;
import com.nantaaditya.example.model.request.admin.CreateOutboundSystemRequest;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * An idempotency key stands for ONE request. Retrying it returns the stored answer; sending a different request
 * under the same key is a client mistake and must not be answered with the first request's result.
 */
class IdempotencyRequestBindingIntegrationTest extends BaseIntegrationTest {

  private static final String CODE = "idem-binding";
  private static final String API_PATH = "/v1/idem-binding";
  private static final String KEY = "order-42";

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
  @Autowired private OutboundHistoryRepository historyRepository;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void seed() throws Exception {
    wireMock.stubFor(post(urlEqualTo(API_PATH)).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json").withBody("{\"charged\":true}")));
    byte[] hostBody = webTestClient.post().uri("/api/outbound/host")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new CreateHostConfigRequest(
            "http://localhost:" + wireMock.port(), 5000, 100, 200, 5000, false, false, null, 0, null, null))
        .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
    long hostId = objectMapper.readTree(hostBody).path("data").path("id").asLong();
    webTestClient.post().uri("/api/outbound").contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new CreateOutboundSystemRequest(CODE, "idem binding", "d", "POST", hostId, API_PATH, true, 24))
        .exchange().expectStatus().isOk();
  }

  @AfterEach
  void cleanup() {
    historyRepository.deleteAll().block();
    authRepository.deleteAll().block();
    resilienceRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    wireMock.resetAll();
  }

  @Test
  @DisplayName("the same key with a different body is rejected with 422 and the upstream is called only for the first")
  void sameKey_differentBody_rejected() throws Exception {
    JsonNode first = execute(KEY, Map.of("amount", 100));
    awaitHistoryRows(1);

    JsonNode second = execute(KEY, Map.of("amount", 999));

    assertThat(first.path("data").path("status").asInt()).isEqualTo(200);
    assertThat(second.path("data").path("status").asInt()).isEqualTo(422);
    assertThat(second.path("data").path("fromCache").asBoolean()).isFalse();
    assertThat(second.toString()).contains("different request");
    wireMock.verify(1, postRequestedFor(urlEqualTo(API_PATH)));
  }

  @Test
  @DisplayName("the same key with the same body is a retry: the stored answer is returned and the upstream is not called again")
  void sameKey_sameBody_replayed() throws Exception {
    execute(KEY, Map.of("amount", 100));
    awaitHistoryRows(1);

    JsonNode retry = execute(KEY, Map.of("amount", 100));

    assertThat(retry.path("data").path("fromCache").asBoolean()).isTrue();
    assertThat(retry.path("data").path("status").asInt()).isEqualTo(200);
    wireMock.verify(1, postRequestedFor(urlEqualTo(API_PATH)));
  }

  @Test
  @DisplayName("the order of keys in the body does not make a retry look like a different request")
  void sameKey_sameBodyDifferentKeyOrder_replayed() throws Exception {
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("amount", 100);
    first.put("currency", "IDR");
    Map<String, Object> reordered = new LinkedHashMap<>();
    reordered.put("currency", "IDR");
    reordered.put("amount", 100);
    execute(KEY, first);
    awaitHistoryRows(1);

    JsonNode retry = execute(KEY, reordered);

    assertThat(retry.path("data").path("fromCache").asBoolean()).isTrue();
    wireMock.verify(1, postRequestedFor(urlEqualTo(API_PATH)));
  }

  @Test
  @DisplayName("the history row stores the fingerprint of the request; a call without a key stores none")
  void historyRow_storesFingerprintOnlyUnderKey() throws Exception {
    execute(KEY, Map.of("amount", 100));
    webTestClient.post().uri("/api/gateway/execute/" + CODE)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new GatewayRequest(Map.of(), Map.of("amount", 5)))
        .exchange().expectStatus().isOk();
    awaitHistoryRows(2);

    List<OutboundHistory> rows = historyRepository.findAll().collectList().block();

    assertThat(rows).filteredOn(OutboundHistory::hasIdempotencyKey)
        .singleElement()
        .satisfies(row -> assertThat(row.getRequestFingerprint()).matches("[0-9a-f]{64}"));
    assertThat(rows).filteredOn(row -> !row.hasIdempotencyKey())
        .singleElement()
        .satisfies(row -> assertThat(row.getRequestFingerprint()).isNull());
  }

  // --- helpers ---

  private JsonNode execute(String key, Map<String, Object> body) throws Exception {
    byte[] response = webTestClient.post().uri("/api/gateway/execute/" + CODE)
        .header(ConstantName.IDEMPOTENT_HEADER, key)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new GatewayRequest(Map.of(), body))
        .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
    return objectMapper.readTree(response);
  }

  private void awaitHistoryRows(long expected) {
    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
        .until(() -> historyRepository.count().block() >= expected);
  }
}
