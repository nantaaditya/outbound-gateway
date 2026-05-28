package com.nantaaditya.example.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.GatewayService;
import java.time.Duration;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class GatewayExecuteValidationIntegrationTest extends BaseIntegrationTest {

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
  @Autowired private GatewayService gatewayService;

  @AfterEach
  void cleanup() {
    resilienceRepository.deleteAll().block();
    authRepository.deleteAll().block();
    historyRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    wireMock.resetAll();
  }

  @Test
  @DisplayName("Inactive system — execute returns 400 even though the system row exists in the DB")
  void execute_withInactiveSystem_returns400() {
    systemRepository.save(OutboundSystem.builder()
        .code("inactive-sys").name("inactive-sys").httpMethod("POST")
        .baseUrl("http://localhost:9999").apiPath("/v1/inactive")
        .active(false).idempotencyTtlHours(24).build()).block();

    GatewayRequest req = new GatewayRequest(Map.of(), Map.of("amount", 100));
    byte[] body = webTestClient.post()
        .uri("/api/gateway/execute/inactive-sys")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(req)
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody().returnResult().getResponseBody();

    assertThat(new String(body)).contains("400");
  }

  @Test
  @DisplayName("Successful execute — outbound_histories row persisted with correct systemId and status")
  void execute_withValidSystem_persistsHistoryRow() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/history-check"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"result\":\"ok\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("history-test", baseUrl, "/v1/history-check");

    GatewayRequest req = new GatewayRequest(Map.of(), Map.of("amount", 42));
    webTestClient.post()
        .uri("/api/gateway/execute/history-test")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(req)
        .exchange()
        .expectStatus().isOk();

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> historyRepository.count().block() >= 1L);

    Long count = historyRepository.count().block();
    assertThat(count).isEqualTo(1L);

    OutboundHistory history = historyRepository.findAll().blockFirst();
    assertThat(history.getOutboundSystemId()).isEqualTo(system.getId());
    assertThat(history.getResponseStatus()).isEqualTo(200);
    assertThat(history.getResponsePayload()).contains("ok");
  }

  @Test
  @DisplayName("Upstream 4xx — gateway returns HTTP 200 envelope, data.status reflects upstream 404")
  void execute_withUpstream4xx_passesThroughStatus() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/not-found"))
        .willReturn(aResponse().withStatus(404)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"error\":\"not found\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    seedSystem("upstream-4xx", baseUrl, "/v1/not-found");

    GatewayRequest req = new GatewayRequest(Map.of(), Map.of("id", "xyz"));
    byte[] body = webTestClient.post()
        .uri("/api/gateway/execute/upstream-4xx")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(req)
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("data").path("status").asInt()).isEqualTo(404);
    assertThat(root.path("data").path("fromCache").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("Null body — gateway forwards request without a body and passes upstream response through")
  void execute_withNullBody_forwardsRequestWithoutBody() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/no-body"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"accepted\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    seedSystem("no-body-test", baseUrl, "/v1/no-body");

    GatewayRequest req = new GatewayRequest(Map.of(), null);
    byte[] body = webTestClient.post()
        .uri("/api/gateway/execute/no-body-test")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(req)
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    assertThat(root.path("data").path("body").path("accepted").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("Query params — gateway forwards query params to the upstream URL")
  void execute_withQueryParams_forwardsToUpstream() {
    wireMock.stubFor(post(urlPathEqualTo("/v1/search"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"hits\":5}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    seedSystem("query-test", baseUrl, "/v1/search");

    GatewayRequest req = new GatewayRequest(Map.of("q", "hello", "page", "1"), Map.of());
    webTestClient.post()
        .uri("/api/gateway/execute/query-test")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(req)
        .exchange()
        .expectStatus().isOk();

    wireMock.verify(1, postRequestedFor(urlPathEqualTo("/v1/search"))
        .withQueryParam("q", equalTo("hello"))
        .withQueryParam("page", equalTo("1")));
  }

  // --- helper ---

  private OutboundSystem seedSystem(String code, String baseUrl, String apiPath) {
    OutboundSystem system = systemRepository.save(OutboundSystem.builder()
        .code(code).name(code).httpMethod("POST").baseUrl(baseUrl).apiPath(apiPath)
        .active(true).idempotencyTtlHours(24).build()).block();

    hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl(baseUrl).connectTimeOutMs(5000).maxConnections(50)
        .pendingAcquireMaxCount(100).pendingAcquireTimeoutMs(5000)
        .disableSslVerification(false).useProxy(false).build()).block();

    resilienceRepository.save(OutboundResilienceConfiguration.builder()
        .outboundSystemId(system.getId())
        .readTimeOutMs(5000)
        .build()).block();

    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.NONE)
        .build()).block();

    gatewayService.evictContext(code);
    return system;
  }
}
