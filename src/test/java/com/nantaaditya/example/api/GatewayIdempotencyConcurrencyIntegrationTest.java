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
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Requests that share an idempotency key and arrive while the first one is still running must not each
 * call the upstream. The upstream here answers slowly (fixed delay) so the requests really overlap.
 */
class GatewayIdempotencyConcurrencyIntegrationTest extends BaseIntegrationTest {

  private static final int UPSTREAM_DELAY_MS = 1500;
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

  @Test
  @DisplayName("two overlapping requests with the same idempotency key call the upstream once")
  void execute_sameKeyOverlapping_callsUpstreamOnce() throws Exception {
    String code = newSystem("/v1/slow");
    stubSlowUpstream("/v1/slow");

    List<JsonNode> responses = fire(2, i -> call(code, "same-key"));

    wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/slow")));
    assertThat(responses).allSatisfy(r -> {
      assertThat(r.path("data").path("status").asInt()).isEqualTo(200);
      assertThat(r.path("data").path("body").path("id").asText()).isEqualTo("txn-1");
    });
    // exactly one request executed, the other one was answered with the stored result
    assertThat(responses.stream().filter(r -> r.path("data").path("fromCache").asBoolean()).count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("many overlapping requests with the same key still call the upstream once")
  void execute_sameKeyManyOverlapping_callsUpstreamOnce() throws Exception {
    String code = newSystem("/v1/slow");
    stubSlowUpstream("/v1/slow");

    List<JsonNode> responses = fire(5, i -> call(code, "same-key"));

    wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/slow")));
    assertThat(responses).hasSize(5);
    assertThat(responses.stream().filter(r -> !r.path("data").path("fromCache").asBoolean()).count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("overlapping requests with different keys are independent and each call the upstream")
  void execute_differentKeysOverlapping_callUpstreamEach() throws Exception {
    String code = newSystem("/v1/slow");
    stubSlowUpstream("/v1/slow");

    List<JsonNode> responses = fire(2, i -> call(code, "key-" + i));

    wireMock.verify(2, postRequestedFor(urlEqualTo("/v1/slow")));
    assertThat(responses).allSatisfy(r ->
        assertThat(r.path("data").path("fromCache").asBoolean()).isFalse());
  }

  @Test
  @DisplayName("requests without an idempotency key are never merged")
  void execute_noKey_neverMerged() throws Exception {
    String code = newSystem("/v1/slow");
    stubSlowUpstream("/v1/slow");

    fire(2, i -> call(code, null));

    wireMock.verify(2, postRequestedFor(urlEqualTo("/v1/slow")));
  }

  // --- helpers ---

  private void stubSlowUpstream(String path) {
    wireMock.stubFor(post(urlEqualTo(path)).willReturn(aResponse()
        .withStatus(200)
        .withFixedDelay(UPSTREAM_DELAY_MS)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"id\":\"txn-1\"}")));
  }

  private JsonNode call(String code, String idempotencyKey) throws Exception {
    var spec = webTestClient.mutate().responseTimeout(Duration.ofSeconds(30)).build().post()
        .uri("/api/gateway/execute/" + code)
        .contentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      spec = spec.header(ConstantName.IDEMPOTENT_HEADER, idempotencyKey);
    }
    byte[] body = spec
        .bodyValue(new GatewayRequest(Map.of(), Map.of("amount", 1)))
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();
    return objectMapper.readTree(body);
  }

  /** Starts {@code count} calls at the same moment and returns all their results. */
  private List<JsonNode> fire(int count, IndexedCall call) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(count);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<JsonNode>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        int index = i;
        Callable<JsonNode> task = () -> {
          go.await();
          return call.run(index);
        };
        futures.add(pool.submit(task));
      }
      go.countDown();
      List<JsonNode> results = new ArrayList<>();
      for (Future<JsonNode> future : futures) {
        results.add(future.get(60, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  @FunctionalInterface
  private interface IndexedCall {
    JsonNode run(int index) throws Exception;
  }

  /** Unique code per test: GatewayServiceImpl caches the loaded context by code. */
  private String newSystem(String apiPath) {
    String code = "idem-conc-" + SEQ.incrementAndGet();
    OutboundHostConfiguration host = hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl("http://localhost:" + wireMock.port())
        .connectTimeOutMs(5000).maxConnections(50).pendingAcquireMaxCount(100)
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
