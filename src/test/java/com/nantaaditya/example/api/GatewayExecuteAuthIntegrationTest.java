package com.nantaaditya.example.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
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
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
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

class GatewayExecuteAuthIntegrationTest extends BaseIntegrationTest {

  static WireMockServer wireMock;
  static KeyPair rsaKeyPair;

  @BeforeAll
  static void startWireMock() throws Exception {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    rsaKeyPair = gen.generateKeyPair();
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
  private EncryptionHelper encryptionHelper;
  @Autowired
  private ObjectMapper objectMapper;

  @AfterEach
  void cleanup() {
    authRepository.deleteAll().block();
    resilienceRepository.deleteAll().block();
    historyRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    wireMock.resetAll();
  }

  @Test
  @DisplayName("Auth NONE — executes without adding any authentication header")
  void execute_withNoneAuth_sendsNoAuthHeader() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/authentication-none"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("authentication-none", baseUrl, "/v1/authentication-none");
    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.NONE)
        .build()).block();

    JsonNode root = executeGateway("authentication-none");

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/authentication-none")));
    awaitHistorySaved(1);
  }

  @Test
  @DisplayName("Auth API_KEY — injects encrypted api key into X-API-Key header")
  void execute_withApiKeyAuth_injectsApiKeyHeader() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/authentication-apikey"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("authentication-apikey", baseUrl, "/v1/authentication-apikey");
    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.API_KEY)
        .apiKey(encryptionHelper.encryptString("test-api-key-value"))
        .tokenKeyName("X-API-Key")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build()).block();

    JsonNode root = executeGateway("authentication-apikey");

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    wireMock.verify(postRequestedFor(urlEqualTo("/v1/authentication-apikey"))
        .withHeader("X-API-Key", equalTo("test-api-key-value")));
    awaitHistorySaved(1);
  }

  @Test
  @DisplayName("Auth BASIC — injects Base64(user:pass) into Authorization header")
  void execute_withBasicAuth_injectsAuthorizationHeader() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/authentication-basic"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("authentication-basic", baseUrl, "/v1/authentication-basic");
    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.BASIC)
        .clientId(encryptionHelper.encryptString("user"))
        .clientSecret(encryptionHelper.encryptString("pass"))
        .build()).block();

    JsonNode root = executeGateway("authentication-basic");

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    // Base64("user:pass") = "dXNlcjpwYXNz"
    wireMock.verify(postRequestedFor(urlEqualTo("/v1/authentication-basic"))
        .withHeader("Authorization", equalTo("Basic dXNlcjpwYXNz")));
    awaitHistorySaved(1);
  }

  @Test
  @DisplayName("Auth BEARER_STATIC — injects static Bearer token into Authorization header")
  void execute_withBearerStaticAuth_injectsBearerHeader() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/authentication-bearer"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("authentication-bearer", baseUrl, "/v1/authentication-bearer");
    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.BEARER_STATIC)
        .clientSecret(encryptionHelper.encryptString("static-token-abc"))
        .tokenPrefix("Bearer")
        .tokenKeyName("Authorization")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build()).block();

    JsonNode root = executeGateway("authentication-bearer");

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    wireMock.verify(postRequestedFor(urlEqualTo("/v1/authentication-bearer"))
        .withHeader("Authorization", equalTo("Bearer static-token-abc")));
    awaitHistorySaved(1);
  }

  @Test
  @DisplayName("Auth OAUTH — fetches token from token endpoint then injects Bearer into Authorization")
  void execute_withOAuthAuth_fetchesTokenAndInjectsHeader() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/oauth/token"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"access_token\":\"fetched-oauth-token\",\"expires_in\":3600}")));
    wireMock.stubFor(post(urlEqualTo("/v1/authentication-oauth"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("authentication-oauth", baseUrl, "/v1/authentication-oauth");
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

    JsonNode root = executeGateway("authentication-oauth");

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    wireMock.verify(1, postRequestedFor(urlEqualTo("/oauth/token")));
    wireMock.verify(postRequestedFor(urlEqualTo("/v1/authentication-oauth"))
        .withHeader("Authorization", equalTo("Bearer fetched-oauth-token")));
    awaitHistorySaved(1);
  }

  @Test
  @DisplayName("Auth HMAC — computes HMAC-SHA256 signature of body and injects into X-Signature header")
  void execute_withHmacAuth_injectsSignatureHeader() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/authentication-hmac"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("authentication-hmac", baseUrl, "/v1/authentication-hmac");
    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.HMAC)
        .clientSecret(encryptionHelper.encryptString("hmac-secret-key"))
        .tokenKeyName("X-Signature")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build()).block();

    JsonNode root = executeGateway("authentication-hmac");

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    wireMock.verify(postRequestedFor(urlEqualTo("/v1/authentication-hmac"))
        .withHeader("X-Signature", matching(".+")));
    awaitHistorySaved(1);
  }

  @Test
  @DisplayName("Auth RSA — computes RSA-SHA256 signature of body and injects into X-RSA-Signature header")
  void execute_withRsaAuth_injectsSignatureHeader() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/authentication-rsa"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("authentication-rsa", baseUrl, "/v1/authentication-rsa");
    // RSA strategy decrypts clientSecret as raw DER bytes then loads as PKCS8 PrivateKey
    byte[] encryptedPrivateKey = encryptionHelper.encrypt(rsaKeyPair.getPrivate().getEncoded());
    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.RSA)
        .clientSecret(encryptedPrivateKey)
        .tokenKeyName("X-RSA-Signature")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build()).block();

    JsonNode root = executeGateway("authentication-rsa");

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    wireMock.verify(postRequestedFor(urlEqualTo("/v1/authentication-rsa"))
        .withHeader("X-RSA-Signature", matching(".+")));
    awaitHistorySaved(1);
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

    resilienceRepository.save(OutboundResilienceConfiguration.builder()
        .outboundSystemId(system.getId()).readTimeOutMs(10000)
        .circuitBreakerEnabled(false).bulkheadEnabled(false)
        .rateLimiterEnabled(false).retryEnabled(false).build()).block();

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

  private void awaitHistorySaved(long expected) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(3))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> historyRepository.count().block() >= expected);
  }
}
