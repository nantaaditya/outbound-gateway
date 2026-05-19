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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GatewayMetricsIntegrationTest extends BaseIntegrationTest {

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

  @Autowired private GatewayService gatewayService;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private OutboundSystemRepository systemRepository;
  @Autowired private OutboundHostConfigurationRepository hostConfigRepository;
  @Autowired private OutboundResilienceConfigurationRepository resilienceRepository;
  @Autowired private OutboundAuthConfigurationRepository authRepository;
  @Autowired private OutboundHistoryRepository historyRepository;

  @AfterEach
  void cleanup() {
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    resilienceRepository.deleteAll().block();
    authRepository.deleteAll().block();
    historyRepository.deleteAll().block();
  }

  @Test
  @DisplayName("execute increments outbound.calls counter with code, http_status, caller_service tags")
  void execute_validCall_incrementsCallCounter() {
    wireMock.stubFor(post(urlEqualTo("/v1/metrics-call"))
        .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

    String code = "gw-metrics-calls";
    setupSystem(code, "http://localhost:" + wireMock.port(), "/v1/metrics-call");

    gatewayService.execute(code, "test-caller", null,
        new GatewayRequest(Map.of(), Map.of("amount", 100))).block();

    Counter counter = meterRegistry.find("outbound.calls")
        .tags("code", code, "http_status", "200", "caller_service", "test-caller")
        .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("execute records call duration in outbound.calls.duration timer")
  void execute_validCall_recordsDurationTimer() {
    wireMock.stubFor(post(urlEqualTo("/v1/metrics-duration"))
        .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

    String code = "gw-metrics-duration";
    setupSystem(code, "http://localhost:" + wireMock.port(), "/v1/metrics-duration");

    gatewayService.execute(code, "test-caller", null,
        new GatewayRequest(Map.of(), Map.of())).block();

    Timer timer = meterRegistry.find("outbound.calls.duration")
        .tags("code", code)
        .timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1);
    assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("execute with null callerService records 'unknown' as caller_service tag")
  void execute_nullCallerService_usesUnknownTag() {
    wireMock.stubFor(post(urlEqualTo("/v1/metrics-null-caller"))
        .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

    String code = "gw-metrics-null-caller";
    setupSystem(code, "http://localhost:" + wireMock.port(), "/v1/metrics-null-caller");

    gatewayService.execute(code, null, null,
        new GatewayRequest(Map.of(), Map.of())).block();

    Counter counter = meterRegistry.find("outbound.calls")
        .tags("code", code, "caller_service", "unknown")
        .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("second call with same idempotency key increments outbound.idempotency.hits")
  void execute_idempotencyHit_incrementsIdempotencyHitsCounter() throws InterruptedException {
    wireMock.stubFor(post(urlEqualTo("/v1/metrics-idem"))
        .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

    String code = "gw-metrics-idem";
    setupSystem(code, "http://localhost:" + wireMock.port(), "/v1/metrics-idem");

    String idemKey = "idem-metrics-key-abc";
    GatewayRequest request = new GatewayRequest(Map.of(), Map.of("amount", 100));

    // First call — executes and saves history (fire-and-forget)
    gatewayService.execute(code, "svc", idemKey, request).block();

    // Allow async history save to persist before second call queries it
    Thread.sleep(300);

    // Second call — should return from cache
    gatewayService.execute(code, "svc", idemKey, request).block();

    Counter idempotencyCounter = meterRegistry.find("outbound.idempotency.hits")
        .tags("code", code)
        .counter();
    assertThat(idempotencyCounter).isNotNull();
    assertThat(idempotencyCounter.count()).isGreaterThanOrEqualTo(1.0);
  }

  @Test
  @DisplayName("failed external call records actual 5xx status code in outbound.calls counter")
  void execute_externalFailure_records5xxStatus() {
    wireMock.stubFor(post(urlEqualTo("/v1/metrics-err"))
        .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"down\"}")));

    String code = "gw-metrics-err";
    setupSystem(code, "http://localhost:" + wireMock.port(), "/v1/metrics-err");

    gatewayService.execute(code, "svc", null, new GatewayRequest(Map.of(), Map.of())).block();

    Counter counter = meterRegistry.find("outbound.calls")
        .tags("code", code, "http_status", "503")
        .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
  }

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
        .pendingAcquireMaxCount(50)
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
