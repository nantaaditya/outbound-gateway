package com.nantaaditya.example.idempotency;

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
import com.nantaaditya.example.entity.OutboundHistory;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Plays the part of a second gateway instance by writing to the reservation table and to
 * outbound_histories directly, exactly as that instance would.
 */
class IdempotencyGuardIntegrationTest extends BaseIntegrationTest {

  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final String KEY = "cross-instance-key";

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
  @Autowired private IdempotencyReservationRepository reservations;
  @Autowired private DatabaseClient databaseClient;
  @Autowired private ObjectMapper objectMapper;

  @AfterEach
  void cleanup() {
    databaseClient.sql("DELETE FROM outbound_idempotency_reservations").then().block();
    historyRepository.deleteAll().block();
    authRepository.deleteAll().block();
    resilienceRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    wireMock.resetAll();
  }

  @Test
  @DisplayName("a request that arrives while another instance runs the same key waits and returns its stored result")
  void execute_otherInstanceRunning_waitsAndReplays() throws Exception {
    OutboundSystem system = newSystem("/v1/cross");
    stubUpstream("/v1/cross");
    reservations.tryAcquire(system.getId(), KEY, "other-instance", Duration.ofSeconds(60)).block();

    CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(() -> callExpectingOk(system.getCode()));
    Thread.sleep(700);
    assertThat(call).isNotDone();              // still waiting for the other instance

    // the other instance finishes: stores its result, then releases the reservation
    historyRepository.saveHistory(OutboundHistory.builder()
        .outboundSystemId(system.getId()).idempotencyKey(KEY).callerService("other")
        .requestMethod("POST").requestUrl("http://x/v1/cross")
        .responseStatus(200).responsePayload("{\"id\":\"from-other-instance\"}")
        .convertedStatus(200).convertedResponse("{\"id\":\"from-other-instance\"}")
        .durationMs(10).retryCount(0).build()).block();
    reservations.release(system.getId(), KEY, "other-instance").block();

    JsonNode response = call.get(10, TimeUnit.SECONDS);
    assertThat(response.path("data").path("fromCache").asBoolean()).isTrue();
    assertThat(response.path("data").path("body").path("id").asText()).isEqualTo("from-other-instance");
    wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/cross")));
  }

  @Test
  @DisplayName("a reservation left behind by a crashed instance is taken over once it has expired")
  void execute_staleReservation_isTakenOverAndExecuted() {
    OutboundSystem system = newSystem("/v1/stale");
    stubUpstream("/v1/stale");
    reservations.tryAcquire(system.getId(), KEY, "dead-instance", Duration.ofSeconds(60)).block();
    databaseClient.sql("UPDATE outbound_idempotency_reservations SET expires_at = NOW() - interval '1 second'")
        .fetch().rowsUpdated().block();

    JsonNode response = callExpectingOk(system.getCode());

    assertThat(response.path("data").path("fromCache").asBoolean()).isFalse();
    wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/stale")));
    assertThat(reservationCount()).isZero();
  }

  @Test
  @DisplayName("the reservation is released after a normal execution")
  void execute_normal_releasesReservation() {
    OutboundSystem system = newSystem("/v1/normal");
    stubUpstream("/v1/normal");

    callExpectingOk(system.getCode());

    assertThat(reservationCount()).isZero();
  }

  @Test
  @DisplayName("the reservation is released when the upstream answers with an error")
  void execute_upstreamError_releasesReservation() {
    OutboundSystem system = newSystem("/v1/error");
    wireMock.stubFor(post(urlEqualTo("/v1/error")).willReturn(aResponse().withStatus(500).withBody("{}")));

    callExpectingOk(system.getCode());

    assertThat(reservationCount()).isZero();
  }

  @Test
  @DisplayName("a request without an idempotency key never creates a reservation")
  void execute_noKey_neverReserves() {
    OutboundSystem system = newSystem("/v1/nokey");
    stubUpstream("/v1/nokey");

    webTestClient.post().uri("/api/gateway/execute/" + system.getCode())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new GatewayRequest(Map.of(), Map.of("amount", 1)))
        .exchange().expectStatus().isOk();

    assertThat(reservationCount()).isZero();
  }

  // --- helpers ---

  private JsonNode callExpectingOk(String code) {
    try {
      byte[] body = webTestClient.mutate().responseTimeout(Duration.ofSeconds(30)).build().post()
          .uri("/api/gateway/execute/" + code)
          .contentType(MediaType.APPLICATION_JSON)
          .header(ConstantName.IDEMPOTENT_HEADER, KEY)
          .bodyValue(new GatewayRequest(Map.of(), Map.of("amount", 1)))
          .exchange()
          .expectStatus().isOk()
          .expectBody().returnResult().getResponseBody();
      return objectMapper.readTree(body);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private long reservationCount() {
    return databaseClient.sql("SELECT COUNT(*) AS c FROM outbound_idempotency_reservations")
        .map(row -> row.get("c", Long.class)).one().block();
  }

  private void stubUpstream(String path) {
    wireMock.stubFor(post(urlEqualTo(path)).willReturn(aResponse()
        .withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"id\":\"fresh\"}")));
  }

  /** Unique code per test: GatewayServiceImpl caches the loaded context by code. */
  private OutboundSystem newSystem(String apiPath) {
    String code = "idem-guard-" + SEQ.incrementAndGet();
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
    return system;
  }
}
