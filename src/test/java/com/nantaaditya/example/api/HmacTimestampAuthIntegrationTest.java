package com.nantaaditya.example.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.request.admin.CreateHostConfigRequest;
import com.nantaaditya.example.model.request.admin.CreateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpsertAuthRequest;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * HMAC_TIMESTAMP end to end: configured through the admin API (settings stored as JSONB), executed through the gateway,
 * and verified the way a partner would: recompute the HMAC from the timestamp header and the body that arrived.
 */
class HmacTimestampAuthIntegrationTest extends BaseIntegrationTest {

  private static final String SECRET = "it-secret";
  private static final String API_PATH = "/v1/hmac-ts";
  private static final AtomicInteger SEQ = new AtomicInteger();

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

  private String code;
  private long hostId;

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
  @DisplayName("the upstream receives an ISO-8601 timestamp and a signature a partner can verify from timestamp + body")
  void execute_sendsVerifiableTimestampAndSignature() throws Exception {
    seedSystem();
    upsertAuth(auth("X-Signature", Map.of("timestampHeader", "X-Timestamp"))).expectStatus().isOk();

    callGateway().expectStatus().isOk();

    LoggedRequest sent = wireMock.findAll(postRequestedFor(urlEqualTo(API_PATH))).get(0);
    String timestamp = sent.getHeader("X-Timestamp");
    assertThat(timestamp).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    assertThat(Instant.parse(timestamp)).isCloseTo(Instant.now(), within(Duration.ofSeconds(30)));
    assertThat(sent.getHeader("X-Signature")).isEqualTo(partnerSignature(timestamp, sent.getBodyAsString()));
  }

  @Test
  @DisplayName("the timestamp setting is stored as JSON and read back, and the secret is not stored in clear")
  void upsert_storesSettingsAndEncryptsSecret() {
    seedSystem();
    upsertAuth(auth("X-Signature", Map.of("timestampHeader", "X-Timestamp"))).expectStatus().isOk();

    var stored = authRepository.findByOutboundSystemId(systemRepository.findByCode(code).block().getId()).block();

    assertThat(stored.getAuthType()).isEqualTo(AuthType.HMAC_TIMESTAMP);
    assertThat(stored.getAuthSettings()).containsEntry("timestampHeader", "X-Timestamp");
    assertThat(new String(stored.getClientSecret(), StandardCharsets.ISO_8859_1)).doesNotContain(SECRET);
  }

  @Test
  @DisplayName("an HMAC_TIMESTAMP configuration without the timestamp header name is rejected with 400")
  void upsert_missingTimestampHeader_rejected() {
    seedSystem();

    upsertAuth(auth("X-Signature", Map.of())).expectStatus().isBadRequest();
  }

  // --- helpers ---

  private String partnerSignature(String timestamp, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return Base64.getEncoder().encodeToString(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
  }

  private UpsertAuthRequest auth(String signatureHeader, Map<String, String> settings) {
    return new UpsertAuthRequest(AuthType.HMAC_TIMESTAMP, null, null, SECRET, false, null, null, null, null,
        null, signatureHeader, null, null, settings);
  }

  private WebTestClient.ResponseSpec upsertAuth(UpsertAuthRequest request) {
    return webTestClient.post().uri("/api/outbound/" + code + "/authentication")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange();
  }

  private WebTestClient.ResponseSpec callGateway() {
    return webTestClient.post().uri("/api/gateway/execute/" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new GatewayRequest(Map.of(), Map.of("amount", 100)))
        .exchange();
  }

  /** Unique code per test: the gateway caches the loaded context by code. */
  private void seedSystem() {
    code = "hmac-ts-" + SEQ.incrementAndGet();
    wireMock.stubFor(post(urlEqualTo(API_PATH)).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));

    byte[] hostBody = webTestClient.post().uri("/api/outbound/host")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new CreateHostConfigRequest(
            "http://localhost:" + wireMock.port(), 5000, 100, 200, 5000, false, false, null, 0, null, null))
        .exchange().expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();
    try {
      JsonNode root = objectMapper.readTree(hostBody);
      hostId = root.path("data").path("id").asLong();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }

    webTestClient.post().uri("/api/outbound")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new CreateOutboundSystemRequest(code, "hmac timestamp", "desc", "POST", hostId, API_PATH, true, 24))
        .exchange().expectStatus().isOk();
  }
}
