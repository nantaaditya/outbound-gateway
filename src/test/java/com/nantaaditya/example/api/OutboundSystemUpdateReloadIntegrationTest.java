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
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.request.admin.CreateHostConfigRequest;
import com.nantaaditya.example.model.request.admin.CreateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpdateOutboundSystemRequest;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * PUT /api/outbound/{code} used to store the change but leave the cached context in place, so a system set to
 * inactive kept serving calls and a changed path was ignored until someone called /reload.
 */
class OutboundSystemUpdateReloadIntegrationTest extends BaseIntegrationTest {

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

  private String code;
  private long hostId;

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
  @DisplayName("deactivating a system takes effect on the very next call (the kill switch works)")
  void update_activeFalse_stopsServingCalls() throws Exception {
    seedSystem("/v1/old");
    callGateway().expectStatus().isOk();   // context is now cached

    put("", "/v1/old", false).expectStatus().isOk();

    callGateway().expectStatus().is4xxClientError();
    wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/old")));
  }

  @Test
  @DisplayName("a changed path applies to the next call when reload is on (explicitly or by default)")
  void update_apiPath_appliesImmediately() throws Exception {
    seedSystem("/v1/old");
    callGateway().expectStatus().isOk();

    put("?reload=true", "/v1/new", true).expectStatus().isOk();
    callGateway().expectStatus().isOk();

    wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/old")));
    wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/new")));
  }

  @Test
  @DisplayName("with reload=false the change is stored but the running context keeps the old path until /reload")
  void update_reloadFalse_appliesOnlyAfterReload() throws Exception {
    seedSystem("/v1/old");
    callGateway().expectStatus().isOk();

    put("?reload=false", "/v1/new", true).expectStatus().isOk();
    callGateway().expectStatus().isOk();

    assertThat(systemRepository.findByCode(code).block().getApiPath()).isEqualTo("/v1/new");   // stored...
    wireMock.verify(2, postRequestedFor(urlEqualTo("/v1/old")));                                // ...not applied
    wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/new")));

    webTestClient.post().uri("/api/outbound/" + code + "/reload").exchange().expectStatus().isOk();
    callGateway().expectStatus().isOk();

    wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/new")));
  }

  // --- helpers ---

  private WebTestClient.ResponseSpec put(String query, String apiPath, boolean active) {
    UpdateOutboundSystemRequest body =
        new UpdateOutboundSystemRequest(code, "update reload test", "POST", hostId, apiPath, active, 24);
    return webTestClient.put()
        .uri("/api/outbound/" + code + query)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  private WebTestClient.ResponseSpec callGateway() {
    return webTestClient.post().uri("/api/gateway/execute/" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new GatewayRequest(Map.of(), Map.of("amount", 100)))
        .exchange();
  }

  /** Unique code per test: the gateway caches the loaded context by code. */
  private void seedSystem(String apiPath) throws Exception {
    code = "upd-reload-" + SEQ.incrementAndGet();
    wireMock.stubFor(post(urlEqualTo("/v1/old")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json").withBody("{\"v\":\"old\"}")));
    wireMock.stubFor(post(urlEqualTo("/v1/new")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json").withBody("{\"v\":\"new\"}")));

    byte[] hostBody = webTestClient.post().uri("/api/outbound/host")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new CreateHostConfigRequest(
            "http://localhost:" + wireMock.port(), 5000, 100, 200, 5000, false, false, null, 0, null, null))
        .exchange().expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();
    JsonNode root = objectMapper.readTree(hostBody);
    hostId = root.path("data").path("id").asLong();

    webTestClient.post().uri("/api/outbound")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new CreateOutboundSystemRequest(
            code, "update reload test", "desc", "POST", hostId, apiPath, true, 24))
        .exchange().expectStatus().isOk();
  }
}
