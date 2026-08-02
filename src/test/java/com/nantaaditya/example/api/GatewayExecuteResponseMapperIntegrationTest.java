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
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResponseMapperConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.GatewayService;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class GatewayExecuteResponseMapperIntegrationTest extends BaseIntegrationTest {

  private static final String JSLT_EXTRACT = "{\"id\": .txId, \"total\": .amount}";

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
  @Autowired private OutboundResponseMapperConfigurationRepository mapperRepository;
  @Autowired private OutboundHistoryRepository historyRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private GatewayService gatewayService;

  @AfterEach
  void cleanup() {
    mapperRepository.deleteAll().block();
    resilienceRepository.deleteAll().block();
    authRepository.deleteAll().block();
    historyRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    wireMock.resetAll();
  }

  @Test
  @DisplayName("No mapper configured — response body and convertedResponse are identical")
  void execute_withNoMapper_passesThroughUnchanged() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/passthrough"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"amount\":100,\"txId\":\"abc-123\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    seedSystem("passthrough-test", baseUrl, "/v1/passthrough");

    JsonNode root = executeGateway("passthrough-test");

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    assertThat(root.path("data").path("convertedStatus").asInt()).isEqualTo(200);
    assertThat(root.path("data").path("body").path("amount").asInt()).isEqualTo(100);
    assertThat(root.path("data").path("convertedResponse").path("amount").asInt()).isEqualTo(100);
  }

  @Test
  @DisplayName("JSLT mapper configured — convertedResponse contains only mapped fields, body is original")
  void execute_withJsltMapper_transformsResponseBody() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/jslt"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"amount\":250,\"txId\":\"tx-999\",\"extra\":\"ignored\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("jslt-test", baseUrl, "/v1/jslt");
    mapperRepository.save(OutboundResponseMapperConfiguration.builder()
        .outboundSystemId(system.getId())
        .converterType(ConverterType.JSLT)
        .mappingScript(JSLT_EXTRACT)
        .active(true)
        .build()).block();

    JsonNode root = executeGateway("jslt-test");

    // original body untouched
    JsonNode body = root.path("data").path("body");
    assertThat(body.path("amount").asInt()).isEqualTo(250);
    assertThat(body.path("txId").asText()).isEqualTo("tx-999");
    assertThat(body.path("extra").asText()).isEqualTo("ignored");

    // convertedResponse contains only the extracted fields
    JsonNode converted = root.path("data").path("convertedResponse");
    assertThat(converted.path("id").asText()).isEqualTo("tx-999");
    assertThat(converted.path("total").asInt()).isEqualTo(250);
    assertThat(converted.path("extra").isMissingNode()).isTrue();
    assertThat(converted.path("amount").isMissingNode()).isTrue();
  }

  @Test
  @DisplayName("JSLT mapper + status remapping — convertedStatus remapped, convertedResponse transformed")
  void execute_withJsltAndStatusRemapping_convertsStatusAndBody() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/remap"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"amount\":500,\"txId\":\"tx-remap\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("remap-test", baseUrl, "/v1/remap");
    mapperRepository.save(OutboundResponseMapperConfiguration.builder()
        .outboundSystemId(system.getId())
        .converterType(ConverterType.JSLT)
        .mappingScript(JSLT_EXTRACT)
        .mappingStatus(Map.of("200", 201))
        .active(true)
        .build()).block();

    JsonNode root = executeGateway("remap-test");

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    assertThat(root.path("data").path("convertedStatus").asInt()).isEqualTo(201);
    assertThat(root.path("data").path("convertedResponse").path("id").asText()).isEqualTo("tx-remap");
    assertThat(root.path("data").path("convertedResponse").path("total").asInt()).isEqualTo(500);
  }

  @Test
  @DisplayName("Inactive mapper — treated as pass-through, response body unchanged")
  void execute_withInactiveMapper_passesThroughUnchanged() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/inactive"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"amount\":77,\"txId\":\"tx-skip\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("inactive-test", baseUrl, "/v1/inactive");
    mapperRepository.save(OutboundResponseMapperConfiguration.builder()
        .outboundSystemId(system.getId())
        .converterType(ConverterType.JSLT)
        .mappingScript(JSLT_EXTRACT)
        .active(false)
        .build()).block();

    JsonNode root = executeGateway("inactive-test");

    // inactive mapper → findByOutboundSystemIdAndActiveTrue returns empty → passthroughMapper()
    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    assertThat(root.path("data").path("body").path("amount").asInt()).isEqualTo(77);
    assertThat(root.path("data").path("body").path("txId").asText()).isEqualTo("tx-skip");
    assertThat(root.path("data").path("convertedResponse").path("amount").asInt()).isEqualTo(77);
  }

  @Test
  @DisplayName("Invalid JSLT script — falls back to original body with mappingFallback=true signal")
  void execute_withInvalidJsltScript_fallsBackToOriginalBodyWithFallbackSignal() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/jslt-broken"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"amount\":100,\"txId\":\"tx-fallback\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("jslt-broken-test", baseUrl, "/v1/jslt-broken");
    mapperRepository.save(OutboundResponseMapperConfiguration.builder()
        .outboundSystemId(system.getId())
        .converterType(ConverterType.JSLT)
        .mappingScript("not valid jslt {{{")
        .active(true)
        .build()).block();

    JsonNode root = executeGateway("jslt-broken-test");

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    assertThat(root.path("data").path("convertedResponse").path("amount").asInt()).isEqualTo(100);
    assertThat(root.path("data").path("convertedResponse").path("txId").asText()).isEqualTo("tx-fallback");
    assertThat(root.path("data").path("mappingFallback").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("Valid JSLT script — mappingFallback is false and convertedResponse is transformed")
  void execute_withValidJsltScript_mappingFallbackIsFalse() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/jslt-ok"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"amount\":200,\"txId\":\"tx-ok\",\"extra\":\"stripped\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("jslt-ok-test", baseUrl, "/v1/jslt-ok");
    mapperRepository.save(OutboundResponseMapperConfiguration.builder()
        .outboundSystemId(system.getId())
        .converterType(ConverterType.JSLT)
        .mappingScript(JSLT_EXTRACT)
        .active(true)
        .build()).block();

    JsonNode root = executeGateway("jslt-ok-test");

    assertThat(root.path("data").path("mappingFallback").asBoolean()).isFalse();
    // mapping ran: convertedResponse contains transformed fields, not the original ones
    JsonNode converted = root.path("data").path("convertedResponse");
    assertThat(converted.path("id").asText()).isEqualTo("tx-ok");
    assertThat(converted.path("total").asInt()).isEqualTo(200);
    assertThat(converted.path("amount").isMissingNode()).isTrue();
    assertThat(converted.path("txId").isMissingNode()).isTrue();
    assertThat(converted.path("extra").isMissingNode()).isTrue();
  }

  @Test
  @DisplayName("Broken JSLT + status remapping — fallback preserves original status, no remapping applied")
  void execute_withInvalidJsltScriptAndStatusMapping_fallbackPreservesOriginalStatus() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/jslt-broken-remap"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"amount\":100,\"txId\":\"tx-broken-remap\"}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("jslt-broken-remap-test", baseUrl, "/v1/jslt-broken-remap");
    mapperRepository.save(OutboundResponseMapperConfiguration.builder()
        .outboundSystemId(system.getId())
        .converterType(ConverterType.JSLT)
        .mappingScript("not valid jslt {{{")
        .mappingStatus(Map.of("200", 201))
        .active(true)
        .build()).block();

    JsonNode root = executeGateway("jslt-broken-remap-test");

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    assertThat(root.path("data").path("convertedStatus").asInt()).isEqualTo(200);
    assertThat(root.path("data").path("mappingFallback").asBoolean()).isTrue();
  }

  // --- helpers ---

  private OutboundSystem seedSystem(String code, String baseUrl, String apiPath) {
    OutboundHostConfiguration hostConfig = hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl(baseUrl).connectTimeOutMs(5000).maxConnections(50)
        .pendingAcquireMaxCount(100).pendingAcquireTimeoutMs(5000)
        .disableSslVerification(false).useProxy(false).build()).block();

    OutboundSystem system = systemRepository.save(OutboundSystem.builder()
        .code(code).name(code).httpMethod("POST").hostConfigurationId(hostConfig.getId()).apiPath(apiPath)
        .active(true).idempotencyTtlHours(24).build()).block();

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
