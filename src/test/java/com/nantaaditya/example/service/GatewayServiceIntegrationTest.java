package com.nantaaditya.example.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

class GatewayServiceIntegrationTest extends BaseIntegrationTest {

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
  private GatewayService gatewayService;

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

  @AfterEach
  void cleanup() {
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    resilienceRepository.deleteAll().block();
    authRepository.deleteAll().block();
    historyRepository.deleteAll().block();
  }

  @Test
  @DisplayName("execute returns 200 response for valid outbound system")
  void execute_validSystem_returns200() {
    wireMock.stubFor(post(urlEqualTo("/v1/charge"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"status\":\"success\",\"id\":\"txn-001\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = saveSystem("payment-gw", baseUrl, "/v1/charge");
    saveHostConfig(baseUrl);
    saveResilience(system.getId());
    saveAuth(system.getId());

    GatewayRequest request = new GatewayRequest(Map.of(), Map.of("amount", 10000));

    StepVerifier.create(
        gatewayService.execute("payment-gw", "caller-svc", null, request))
        .assertNext(response -> {
          assertThat(response.status()).isEqualTo(200);
          assertThat(response.body()).contains("success");
          assertThat(response.fromCache()).isFalse();
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("execute returns cached response on second call with same idempotency key")
  void execute_sameIdempotencyKey_returnsCachedResponse() {
    wireMock.stubFor(post(urlEqualTo("/v1/charge"))
        .willReturn(aResponse()
            .withStatus(200)
            .withBody("{\"status\":\"success\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = saveSystem("idem-gw", baseUrl, "/v1/charge");
    saveHostConfig(baseUrl);
    saveResilience(system.getId());
    saveAuth(system.getId());

    GatewayRequest request = new GatewayRequest(Map.of(), Map.of("amount", 5000));
    String idemKey = "unique-idem-key-123";

    // First call — should hit WireMock
    gatewayService.execute("idem-gw", "caller-svc", idemKey, request).block();

    // Second call — should return from cache (history)
    StepVerifier.create(
        gatewayService.execute("idem-gw", "caller-svc", idemKey, request))
        .assertNext(response -> {
          assertThat(response.status()).isEqualTo(200);
          assertThat(response.fromCache()).isTrue();
        })
        .verifyComplete();
  }

  private OutboundSystem saveSystem(String code, String baseUrl, String apiPath) {
    return systemRepository.save(OutboundSystem.builder()
        .code(code)
        .name(code)
        .httpMethod("POST")
        .baseUrl(baseUrl)
        .apiPath(apiPath)
        .active(true)
        .idempotencyTtlHours(24)
        .build()).block();
  }

  private void saveHostConfig(String baseUrl) {
    hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl(baseUrl)
        .connectTimeOutMs(5000)
        .maxConnections(50)
        .pendingAcquireMaxCount(50)
        .pendingAcquireTimeoutMs(5000)
        .disableSslVerification(false)
        .useProxy(false)
        .build()).block();
  }

  private void saveResilience(Long systemId) {
    resilienceRepository.save(OutboundResilienceConfiguration.builder()
        .outboundSystemId(systemId)
        .readTimeOutMs(10000)
        .cbFailureRateThreshold(50f)
        .cbSlowCallRateThreshold(100f)
        .cbSlowCallDurationMs(60000)
        .cbPermittedInHalfOpen(10)
        .cbSlidingWindowSize(100)
        .cbWaitDurationOpenMs(60000)
        .bhMaxConcurrentCalls(25)
        .bhMaxWaitDurationMs(0)
        .rlLimitForPeriod(100)
        .rlLimitRefreshPeriodMs(1000)
        .rlTimeoutDurationMs(0)
        .retryMaxAttempts(1)
        .retryWaitDurationMs(100)
        .retryOnStatusCodes("500,502,503")
        .build()).block();
  }

  private void saveAuth(Long systemId) {
    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(systemId)
        .authType(AuthType.NONE)
        .build()).block();
  }

  @Test
  @DisplayName("execute succeeds as NONE authentication when no authentication config row exists")
  void execute_systemWithoutAuthConfig_succeedsAsNone() {
    wireMock.stubFor(post(urlEqualTo("/v1/ping"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"pong\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = saveSystem("no-authentication-gw", baseUrl, "/v1/ping");
    saveHostConfig(baseUrl);
    saveResilience(system.getId());
    // intentionally no saveAuth call

    GatewayRequest request = new GatewayRequest(java.util.Map.of(), java.util.Map.of());

    StepVerifier.create(
        gatewayService.execute("no-authentication-gw", "caller-svc", null, request))
        .assertNext(response -> {
          assertThat(response.status()).isEqualTo(200);
          assertThat(response.fromCache()).isFalse();
        })
        .verifyComplete();
  }
}
