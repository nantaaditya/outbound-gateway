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
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.request.admin.CreateHostConfigRequest;
import com.nantaaditya.example.model.request.admin.CreateOutboundSystemRequest;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The idempotency key is client input that ends up in two {@code VARCHAR(255)} columns and in a lookup key. A key the
 * database cannot hold used to surface as a 500 from deep inside the reservation insert; it is now refused at the
 * edge with a 400 before anything runs.
 */
class IdempotencyKeyValidationIntegrationTest extends BaseIntegrationTest {

  private static final String CODE = "idem-validation";
  private static final String API_PATH = "/v1/idem-validation";

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
        .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
    byte[] hostBody = webTestClient.post().uri("/api/outbound/host")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new CreateHostConfigRequest(
            "http://localhost:" + wireMock.port(), 5000, 100, 200, 5000, false, false, null, 0, null, null))
        .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
    long hostId = objectMapper.readTree(hostBody).path("data").path("id").asLong();
    webTestClient.post().uri("/api/outbound").contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new CreateOutboundSystemRequest(CODE, "idem validation", "d", "POST", hostId, API_PATH, true, 24))
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
  @DisplayName("a key longer than the 255 characters the database holds is refused with 400 and nothing is sent upstream")
  void keyTooLong_is400_andNothingSent() throws Exception {
    JsonNode body = executeExpecting400("k".repeat(256));

    assertThat(body.path("response").path("code").asText()).isEqualTo("900");
    wireMock.verify(0, postRequestedFor(urlEqualTo(API_PATH)));
  }

  @Test
  @DisplayName("a key that is not printable ASCII is refused with 400")
  void keyWithNonAscii_is400() throws Exception {
    executeExpecting400("kéy-中");

    wireMock.verify(0, postRequestedFor(urlEqualTo(API_PATH)));
  }

  @Test
  @DisplayName("a key of exactly 255 characters and a UUID are accepted")
  void validKeys_areAccepted() {
    execute("k".repeat(255)).expectStatus().isOk();
    execute("123e4567-e89b-12d3-a456-426614174000").expectStatus().isOk();

    wireMock.verify(2, postRequestedFor(urlEqualTo(API_PATH)));
  }

  @Test
  @DisplayName("a request without a key is unaffected")
  void noKey_isAccepted() {
    webTestClient.post().uri("/api/gateway/execute/" + CODE)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new GatewayRequest(Map.of(), Map.of("a", 1)))
        .exchange().expectStatus().isOk();
  }

  // --- helpers ---

  private WebTestClient.ResponseSpec execute(String key) {
    return webTestClient.post().uri("/api/gateway/execute/" + CODE)
        .header(ConstantName.IDEMPOTENT_HEADER, key)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new GatewayRequest(Map.of(), Map.of("a", key.length())))
        .exchange();
  }

  private JsonNode executeExpecting400(String key) throws Exception {
    byte[] response = execute(key).expectStatus().isBadRequest().expectBody().returnResult().getResponseBody();
    return objectMapper.readTree(response);
  }
}
