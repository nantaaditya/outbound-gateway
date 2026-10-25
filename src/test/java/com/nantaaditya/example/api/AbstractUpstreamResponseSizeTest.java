package com.nantaaditya.example.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Shared setup for the upstream-response-size tests; the subclasses differ only in the configured limit. */
abstract class AbstractUpstreamResponseSizeTest extends BaseIntegrationTest {

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

  @AfterEach
  void cleanup() {
    historyRepository.deleteAll().block();
    authRepository.deleteAll().block();
    resilienceRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    wireMock.resetAll();
  }

  /** Calls a fresh system whose upstream answers 200 with a JSON body of roughly {@code bytes} bytes. */
  JsonNode callUpstreamReturning(int bytes) throws Exception {
    String path = "/v1/large-" + SEQ.incrementAndGet();
    wireMock.stubFor(post(urlEqualTo(path)).willReturn(aResponse()
        .withStatus(200).withHeader("Content-Type", "application/json")
        .withBody("{\"payload\":\"" + "x".repeat(bytes) + "\"}")));
    String code = newSystem(path);

    // the test client has its own 256 KB default; raise it so it can read the large answer the gateway now returns
    byte[] response = webTestClient.mutate()
        .responseTimeout(Duration.ofSeconds(30))
        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
        .build().post()
        .uri("/api/gateway/execute/" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new GatewayRequest(Map.of(), Map.of("amount", 1)))
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();
    return objectMapper.readTree(response);
  }

  /** Unique code per test: GatewayServiceImpl caches the loaded context by code. */
  private String newSystem(String apiPath) {
    String code = "size-" + SEQ.incrementAndGet();
    OutboundHostConfiguration host = hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl("http://localhost:" + wireMock.port())
        .connectTimeOutMs(5000).maxConnections(10).pendingAcquireMaxCount(10)
        .pendingAcquireTimeoutMs(5000).disableSslVerification(false).useProxy(false)
        .build()).block();
    OutboundSystem system = systemRepository.save(OutboundSystem.builder()
        .code(code).name(code).httpMethod("POST").hostConfigurationId(host.getId())
        .apiPath(apiPath).active(true).idempotencyTtlHours(24).build()).block();
    resilienceRepository.save(OutboundResilienceConfiguration.builder()
        .outboundSystemId(system.getId()).readTimeOutMs(20000)
        .circuitBreakerEnabled(false).bulkheadEnabled(false)
        .rateLimiterEnabled(false).retryEnabled(false).build()).block();
    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId()).authType(AuthType.NONE).build()).block();
    return code;
  }
}
