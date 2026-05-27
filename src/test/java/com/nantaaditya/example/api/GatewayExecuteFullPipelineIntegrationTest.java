package com.nantaaditya.example.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHeaderConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResponseMapperConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.GatewayService;
import com.nantaaditya.example.service.impl.ResilienceRegistryService;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class GatewayExecuteFullPipelineIntegrationTest extends BaseIntegrationTest {

  private static final List<String> PIPE_CODES =
      List.of("pipe-a", "pipe-b", "pipe-c", "pipe-d");

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
  @Autowired private OutboundHeaderConfigurationRepository headerConfigRepository;
  @Autowired private OutboundResponseMapperConfigurationRepository mapperRepository;
  @Autowired private OutboundHistoryRepository historyRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private EncryptionHelper encryptionHelper;
  @Autowired private GatewayService gatewayService;
  @Autowired private ResilienceRegistryService resilienceRegistryService;

  @AfterEach
  void cleanup() {
    PIPE_CODES.forEach(code -> {
      gatewayService.evictContext(code);
      resilienceRegistryService.reload(code);
    });
    mapperRepository.deleteAll().block();
    headerConfigRepository.deleteAll().block();
    resilienceRepository.deleteAll().block();
    authRepository.deleteAll().block();
    historyRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    wireMock.resetAll();
  }

  // --- Pipeline A: API_KEY + Retry + static header + JSLT mapper ---

  @Test
  @DisplayName("Pipeline A: API_KEY + Retry (500→200) + X-Custom-Header + JSLT — 2 upstream requests, 1 history, convertedResponse stripped")
  void pipelineA_apiKeyRetryStaticHeaderJsltMapper() throws Exception {
    String code = "pipe-a";

    wireMock.stubFor(post(urlEqualTo("/v1/pipe-a"))
        .inScenario("pipe-a-retry")
        .whenScenarioStateIs(Scenario.STARTED)
        .willReturn(aResponse().withStatus(500)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"status\":500,\"message\":\"upstream error\",\"extra\":\"ignored\"}"))
        .willSetStateTo("SUCCESS"));

    wireMock.stubFor(post(urlEqualTo("/v1/pipe-a"))
        .inScenario("pipe-a-retry")
        .whenScenarioStateIs("SUCCESS")
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"status\":200,\"message\":\"ok\",\"extra\":\"ignored\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedBase(code, baseUrl, "/v1/pipe-a");

    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.API_KEY)
        .apiKey(encryptionHelper.encryptString("pipe-a-api-key"))
        .tokenKeyName("X-API-Key")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build()).block();

    resilienceRepository.save(OutboundResilienceConfiguration.builder()
        .outboundSystemId(system.getId())
        .readTimeOutMs(5000)
        .retryEnabled(true)
        .retryMaxAttempts(2)
        .retryWaitDurationMs(0)
        .retryOnStatusCodes("500")
        .build()).block();

    seedStaticHeader(system.getId(), "X-Custom-Header", "custom-value");

    mapperRepository.save(OutboundResponseMapperConfiguration.builder()
        .outboundSystemId(system.getId())
        .converterType(ConverterType.JSLT)
        .mappingScript("{\"status\": .status, \"message\": .message}")
        .active(true)
        .build()).block();

    JsonNode root = executeGateway(code);

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    JsonNode converted = root.path("data").path("convertedResponse");
    assertThat(converted.path("status").asInt()).isEqualTo(200);
    assertThat(converted.path("message").asText()).isEqualTo("ok");
    assertThat(converted.path("extra").isMissingNode()).isTrue();

    // Both retry attempts must carry API_KEY and static header to upstream
    wireMock.verify(2, postRequestedFor(urlEqualTo("/v1/pipe-a"))
        .withHeader("X-API-Key", equalTo("pipe-a-api-key"))
        .withHeader("X-Custom-Header", equalTo("custom-value")));

    // Retry is transparent — only the final result is stored as one history row
    awaitHistorySaved(system.getId(), 1L);
  }

  // --- Pipeline B: BASIC + Circuit Breaker + static header ---

  @Test
  @DisplayName("Pipeline B: BASIC + Circuit Breaker (forced open) + X-Source — 1 upstream request, 2nd blocked by CB with 503")
  void pipelineB_basicAuthCircuitBreakerStaticHeader() throws Exception {
    String code = "pipe-b";

    wireMock.stubFor(post(urlEqualTo("/v1/pipe-b"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedBase(code, baseUrl, "/v1/pipe-b");

    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.BASIC)
        .clientId(encryptionHelper.encryptString("pipe-b-user"))
        .clientSecret(encryptionHelper.encryptString("pipe-b-pass"))
        .build()).block();

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

    seedStaticHeader(system.getId(), "X-Source", "pipeline-test");

    // Call 1: warms context (CB instance registered), upstream hit
    JsonNode call1 = executeGateway(code);
    assertThat(call1.path("data").path("status").asInt()).isEqualTo(200);

    // Verify BASIC authentication and static header were forwarded
    String expectedBasic = Base64.getEncoder()
        .encodeToString("pipe-b-user:pipe-b-pass".getBytes());
    wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/pipe-b"))
        .withHeader("Authorization", equalTo("Basic " + expectedBasic))
        .withHeader("X-Source", equalTo("pipeline-test")));

    // Force CB to OPEN — next call is rejected immediately without hitting upstream
    resilienceRegistryService.getOrCreateCircuitBreaker(code, resilience)
        .transitionToOpenState();

    // Call 2: CB OPEN → blocked, gateway returns 503
    JsonNode call2 = executeGateway(code);
    assertThat(call2.path("data").path("status").asInt()).isEqualTo(503);
    assertThat(call2.path("data").path("body").asText()).contains("circuit open");

    // WireMock received only 1 request (2nd call blocked by CB)
    wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/pipe-b")));

    // 2 history rows: call-1 (200) and call-2 (503)
    awaitHistorySaved(system.getId(), 2L);
  }

  // --- Pipeline C: BEARER_STATIC + Rate Limiter + static header ---

  @Test
  @DisplayName("Pipeline C: BEARER_STATIC + Rate Limiter (1 permit/60 s) + X-App-Version — 1st 200, 2nd 429")
  void pipelineC_bearerStaticRateLimiterStaticHeader() throws Exception {
    String code = "pipe-c";

    wireMock.stubFor(post(urlEqualTo("/v1/pipe-c"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"result\":\"ok\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedBase(code, baseUrl, "/v1/pipe-c");

    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.BEARER_STATIC)
        .clientSecret(encryptionHelper.encryptString("static-token-abc"))
        .tokenPrefix("Bearer")
        .tokenKeyName("Authorization")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build()).block();

    resilienceRepository.save(OutboundResilienceConfiguration.builder()
        .outboundSystemId(system.getId())
        .readTimeOutMs(5000)
        .rateLimiterEnabled(true)
        .rlLimitForPeriod(1)
        .rlLimitRefreshPeriodMs(60000)
        .rlTimeoutDurationMs(0)
        .build()).block();

    seedStaticHeader(system.getId(), "X-App-Version", "v1.0.0");

    // Call 1: consumes the single permit; upstream gets the request
    JsonNode call1 = executeGateway(code);
    assertThat(call1.path("data").path("status").asInt()).isEqualTo(200);

    wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/pipe-c"))
        .withHeader("Authorization", equalTo("Bearer static-token-abc"))
        .withHeader("X-App-Version", equalTo("v1.0.0")));

    // Call 2: no permits left (60 s refresh period, timeout=0) → 429 immediately
    JsonNode call2 = executeGateway(code);
    assertThat(call2.path("data").path("status").asInt()).isEqualTo(429);
    assertThat(call2.path("data").path("body").asText()).contains("rate limited");

    // WireMock still received only 1 request (2nd blocked by rate limiter)
    wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/pipe-c")));

    // 2 history rows: call-1 (200) and call-2 (429)
    awaitHistorySaved(system.getId(), 2L);
  }

  // --- Pipeline D: OAUTH + bypass resilience + static header + JSLT mapper ---

  @Test
  @DisplayName("Pipeline D: OAUTH + bypass resilience + X-Tenant + JSLT — token fetched once, nested fields extracted, sku absent")
  void pipelineD_oauthBypassResilienceStaticHeaderJsltMapper() throws Exception {
    String code = "pipe-d";

    wireMock.stubFor(post(urlEqualTo("/oauth/token"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"access_token\":\"fetched-token-xyz\",\"expires_in\":3600}")));

    wireMock.stubFor(post(urlEqualTo("/v1/pipe-d"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"data\":{\"id\":\"d-001\",\"name\":\"Alice\",\"sku\":\"abc123\"}}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedBase(code, baseUrl, "/v1/pipe-d");

    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.OAUTH)
        .clientId(encryptionHelper.encryptString("oauth-client"))
        .clientSecret(encryptionHelper.encryptString("oauth-secret"))
        .autoRefresh(true)
        .tokenMethod("POST")
        .tokenUrl(baseUrl + "/oauth/token")
        .tokenResponsePath("access_token")
        .tokenResponseExpiryPath("expires_in")
        .tokenPrefix("Bearer")
        .tokenKeyName("Authorization")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build()).block();

    // No resilience row saved → loadFromDb defaults to bypass() — all operators skipped

    seedStaticHeader(system.getId(), "X-Tenant", "tenant-001");

    mapperRepository.save(OutboundResponseMapperConfiguration.builder()
        .outboundSystemId(system.getId())
        .converterType(ConverterType.JSLT)
        .mappingScript("{\"id\": .data.id, \"name\": .data.name}")
        .active(true)
        .build()).block();

    JsonNode root = executeGateway(code);

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    JsonNode converted = root.path("data").path("convertedResponse");
    assertThat(converted.path("id").asText()).isEqualTo("d-001");
    assertThat(converted.path("name").asText()).isEqualTo("Alice");
    assertThat(converted.path("sku").isMissingNode()).isTrue();

    // OAuth token endpoint called once
    wireMock.verify(1, postRequestedFor(urlEqualTo("/oauth/token")));

    // Main endpoint received the fetched token and X-Tenant header
    wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/pipe-d"))
        .withHeader("Authorization", equalTo("Bearer fetched-token-xyz"))
        .withHeader("X-Tenant", equalTo("tenant-001")));

    awaitHistorySaved(system.getId(), 1L);
  }

  // --- helpers ---

  private OutboundSystem seedBase(String code, String baseUrl, String apiPath) {
    OutboundSystem system = systemRepository.save(OutboundSystem.builder()
        .code(code).name(code).httpMethod("POST").baseUrl(baseUrl).apiPath(apiPath)
        .active(true).idempotencyTtlHours(24).build()).block();

    hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl(baseUrl).connectTimeOutMs(5000).maxConnections(50)
        .pendingAcquireMaxCount(100).pendingAcquireTimeoutMs(5000)
        .disableSslVerification(false).useProxy(false).build()).block();

    return system;
  }

  private void seedStaticHeader(long systemId, String name, String value) {
    Map<String, Object> headersMap = new HashMap<>();
    headersMap.put(name, List.of(value));
    headerConfigRepository.save(OutboundHeaderConfiguration.builder()
        .outboundSystemId(systemId)
        .headers(headersMap)
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

  private void awaitHistorySaved(long systemId, long expected) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> historyRepository.countByFilters(systemId, 0, null, null).block() >= expected);
  }
}
