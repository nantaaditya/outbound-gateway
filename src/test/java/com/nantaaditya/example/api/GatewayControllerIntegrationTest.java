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

class GatewayControllerIntegrationTest extends BaseIntegrationTest {

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
  private OutboundHostConfigurationRepository hostConfigRepository;
  @Autowired
  private OutboundResilienceConfigurationRepository resilienceRepository;
  @Autowired
  private OutboundAuthConfigurationRepository authRepository;
  @Autowired
  private OutboundHistoryRepository historyRepository;
  @Autowired
  private ObjectMapper objectMapper;

  @AfterEach
  void cleanup() {
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    resilienceRepository.deleteAll().block();
    authRepository.deleteAll().block();
    historyRepository.deleteAll().block();
  }

  @Test
  @DisplayName("POST /api/gateway/execute/{code} — wraps external response in BaseResponse envelope")
  void execute_validSystem_returnsSuccessEnvelope() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/charge"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"id\":\"txn-001\",\"status\":\"SUCCESS\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    setupSystem("gw-charge", baseUrl, "/v1/charge");

    GatewayRequest req = new GatewayRequest(Map.of(), Map.of("amount", 10000));

    byte[] body = webTestClient.post()
        .uri("/api/gateway/execute/gw-charge")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Caller-Service", "payment-svc")
        .bodyValue(req)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("response").path("code").asText()).isEqualTo("000");
    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    assertThat(root.path("data").path("body").path("id").asText()).isEqualTo("txn-001");
    assertThat(root.path("data").path("fromCache").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("POST /api/gateway/execute/{code} — second call with same idempotency key returns fromCache=true")
  void execute_sameIdempotencyKey_returnsCachedOnSecondCall() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/idem"))
        .willReturn(aResponse()
            .withStatus(200)
            .withBody("{\"id\":\"cached-txn\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    setupSystem("gw-idem", baseUrl, "/v1/idem");

    GatewayRequest req = new GatewayRequest(Map.of(), Map.of("amount", 5000));

    // First call — hits WireMock
    webTestClient.post()
        .uri("/api/gateway/execute/gw-idem")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Idempotency-Key", "key-xyz")
        .bodyValue(req)
        .exchange()
        .expectStatus().isOk();

    // Allow async history save to persist
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> historyRepository.count().block() >= 1L);

    // Second call — should return from cache
    byte[] body = webTestClient.post()
        .uri("/api/gateway/execute/gw-idem")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Idempotency-Key", "key-xyz")
        .bodyValue(req)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();

    JsonNode root = objectMapper.readTree(body);
    assertThat(root.path("data").path("fromCache").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("POST /api/gateway/execute/{code} — unknown code returns HTTP 400")
  void execute_unknownCode_returns400() {
    GatewayRequest req = new GatewayRequest(Map.of(), Map.of());

    byte[] body = webTestClient.post()
        .uri("/api/gateway/execute/no-such-system")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(req)
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .returnResult()
        .getResponseBody();

    String json = new String(body);
    assertThat(json).contains("400");
  }

  // --- helpers ---

  private void setupSystem(String code, String baseUrl, String apiPath) {
    OutboundSystem system = systemRepository.save(OutboundSystem.builder()
        .code(code)
        .name(code)
        .httpMethod("POST")
        .baseUrl(baseUrl)
        .apiPath(apiPath)
        .active(true)
        .idempotencyTtlHours(24)
        .build()).block();

    hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl(baseUrl)
        .connectTimeOutMs(5000)
        .maxConnections(50)
        .pendingAcquireMaxCount(100)
        .pendingAcquireTimeoutMs(5000)
        .disableSslVerification(false)
        .useProxy(false)
        .build()).block();

    resilienceRepository.save(OutboundResilienceConfiguration.builder()
        .outboundSystemId(system.getId())
        .readTimeOutMs(10000)
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
}
