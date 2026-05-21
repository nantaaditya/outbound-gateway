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
import com.nantaaditya.example.service.GatewayService;
import com.nantaaditya.example.service.impl.ResilienceRegistryService;
import io.github.resilience4j.bulkhead.Bulkhead;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class GatewayExecuteResilienceIntegrationTest extends BaseIntegrationTest {

  private static final List<String> TEST_CODES = List.of(
      "cb-test", "bh-test", "rl-test", "retry-test", "timeout-test");

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
  @Autowired
  private ResilienceRegistryService resilienceRegistryService;
  @Autowired
  private GatewayService gatewayService;

  @AfterEach
  void cleanup() {
    TEST_CODES.forEach(code -> {
      gatewayService.evictContext(code);
      resilienceRegistryService.reload(code);
    });
    resilienceRepository.deleteAll().block();
    authRepository.deleteAll().block();
    historyRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    wireMock.resetAll();
  }

  @Test
  @DisplayName("Circuit Breaker open — gateway returns 503 when CB is forced to OPEN state")
  void execute_withCircuitBreakerOpen_returns503() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/cb-test"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("cb-test", baseUrl, "/v1/cb-test");
    OutboundResilienceConfiguration resilience = resilienceRepository.save(
        OutboundResilienceConfiguration.builder()
            .outboundSystemId(system.getId())
            .readTimeOutMs(5000)
            .circuitBreakerEnabled(true)
            .cbFailureRateThreshold(50f)
            .cbSlowCallRateThreshold(100f)
            .cbSlowCallDurationMs(60000)
            .cbPermittedInHalfOpen(1)
            .cbSlidingWindowSize(2)
            .cbWaitDurationOpenMs(60000)
            .build()).block();
    seedAuth("cb-test", system.getId());

    // Warm context so the CB instance is registered
    executeGateway("cb-test");

    // Force CB to OPEN — next call is rejected immediately without hitting upstream
    resilienceRegistryService.getOrCreateCircuitBreaker("cb-test", resilience)
        .transitionToOpenState();

    JsonNode root = executeGateway("cb-test");
    assertThat(root.path("data").path("status").asInt()).isEqualTo(503);
    assertThat(root.path("data").path("body").asText()).contains("circuit open");
  }

  @Test
  @DisplayName("Bulkhead full — gateway returns 503 when all concurrent slots are occupied")
  void execute_withBulkheadFull_returns503() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/bh-test"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("bh-test", baseUrl, "/v1/bh-test");
    OutboundResilienceConfiguration resilience = resilienceRepository.save(
        OutboundResilienceConfiguration.builder()
            .outboundSystemId(system.getId())
            .readTimeOutMs(5000)
            .bulkheadEnabled(true)
            .bhMaxConcurrentCalls(1)
            .bhMaxWaitDurationMs(0)
            .build()).block();
    seedAuth("bh-test", system.getId());

    // Warm context so the Bulkhead instance is registered
    executeGateway("bh-test");

    // Occupy the only available concurrent slot
    Bulkhead bulkhead = resilienceRegistryService.getOrCreateBulkhead("bh-test", resilience);
    bulkhead.tryAcquirePermission();

    JsonNode root = executeGateway("bh-test");
    assertThat(root.path("data").path("status").asInt()).isEqualTo(503);
    assertThat(root.path("data").path("body").asText()).contains("bulkhead full");
  }

  @Test
  @DisplayName("Rate limiter exceeded — gateway returns 429 on second call within the refresh period")
  void execute_withRateLimitExceeded_returns429() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/rl-test"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("rl-test", baseUrl, "/v1/rl-test");
    resilienceRepository.save(OutboundResilienceConfiguration.builder()
        .outboundSystemId(system.getId())
        .readTimeOutMs(5000)
        .rateLimiterEnabled(true)
        .rlLimitForPeriod(1)
        .rlLimitRefreshPeriodMs(60000)
        .rlTimeoutDurationMs(0)
        .build()).block();
    seedAuth("rl-test", system.getId());

    // First call consumes the single permit
    JsonNode first = executeGateway("rl-test");
    assertThat(first.path("data").path("status").asInt()).isEqualTo(200);

    // Second call within the 60 s refresh period — no permits remain
    JsonNode second = executeGateway("rl-test");
    assertThat(second.path("data").path("status").asInt()).isEqualTo(429);
    assertThat(second.path("data").path("body").asText()).contains("rate limited");
  }

  @Test
  @DisplayName("Retry exhausted — upstream 500 triggers 3 attempts, last 502 response is returned")
  void execute_withRetryOnResult_exhaustsAttemptsAndForwardsLastResponse() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/retry-test"))
        .willReturn(aResponse().withStatus(500)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"error\":\"server error\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("retry-test", baseUrl, "/v1/retry-test");
    resilienceRepository.save(OutboundResilienceConfiguration.builder()
        .outboundSystemId(system.getId())
        .readTimeOutMs(5000)
        .retryEnabled(true)
        .retryMaxAttempts(3)
        .retryWaitDurationMs(0)
        .retryOnStatusCodes("500")
        .build()).block();
    seedAuth("retry-test", system.getId());

    // Resilience4j 2.x reactive retryOnResult: after maxAttempts the last result is forwarded
    // (not wrapped in MaxRetriesExceededException). Retries do fire — WireMock receives all 3 calls.
    JsonNode root = executeGateway("retry-test");
    assertThat(root.path("data").path("status").asInt()).isEqualTo(502);
    wireMock.verify(3, postRequestedFor(urlEqualTo("/v1/retry-test")));
  }

  @Test
  @DisplayName("Read timeout — gateway returns 504 when upstream does not respond within 100 ms")
  void execute_withReadTimeout_returns504() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/timeout-test"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true}")
            .withFixedDelay(500)));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("timeout-test", baseUrl, "/v1/timeout-test");
    resilienceRepository.save(OutboundResilienceConfiguration.builder()
        .outboundSystemId(system.getId())
        .readTimeOutMs(100)
        .build()).block();
    seedAuth("timeout-test", system.getId());

    JsonNode root = executeGateway("timeout-test");
    assertThat(root.path("data").path("status").asInt()).isEqualTo(504);
    assertThat(root.path("data").path("body").asText()).contains("gateway timeout");
  }

  // --- helpers ---

  private OutboundSystem seedSystem(String code, String baseUrl, String apiPath) {
    OutboundSystem system = systemRepository.save(OutboundSystem.builder()
        .code(code).name(code).httpMethod("POST").baseUrl(baseUrl).apiPath(apiPath)
        .active(true).idempotencyTtlHours(24).build()).block();

    hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl(baseUrl).connectTimeOutMs(5000).maxConnections(50)
        .pendingAcquireMaxCount(100).pendingAcquireTimeoutMs(5000)
        .disableSslVerification(false).useProxy(false).build()).block();

    return system;
  }

  private void seedAuth(String code, long systemId) {
    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(systemId)
        .authType(AuthType.NONE)
        .build()).block();
  }

  private JsonNode executeGateway(String code) throws Exception {
    GatewayRequest req = new GatewayRequest(Map.of(), Map.of("amount", 100));
    byte[] body = webTestClient.post()
        .uri("/api/gateway/execute/" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(req)
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();
    return objectMapper.readTree(body);
  }
}
