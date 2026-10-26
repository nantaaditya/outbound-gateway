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
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.crypto.EncryptionHelper;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
  @DisplayName("Auth HMAC — X-Signature verifies against the exact body WireMock received")
  void execute_withHmacAuth_injectsSignatureHeader() throws Exception {
    wireMock.stubFor(post(urlEqualTo("/v1/authentication-hmac"))
        .willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true}")));

    String baseUrl = "http://localhost:" + wireMock.port();
    OutboundSystem system = seedSystem("authentication-hmac", baseUrl, "/v1/authentication-hmac");
    String hmacSecret = "hmac-secret-key";
    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.HMAC)
        .clientSecret(encryptionHelper.encryptString(hmacSecret))
        .tokenKeyName("X-Signature")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build()).block();

    // A body containing an actual newline/tab forces Jackson to JSON-escape it as a literal
    // "\n"/"\t" sequence in the transmitted text — precisely what GsonHelper.cleanJson's
    // character-stripping would silently remove if the signed string ever diverged from the
    // wire body again. A flat {"amount":100} body doesn't exercise that path at all.
    JsonNode root = executeGatewayWithBody("authentication-hmac", Map.of("note", "line1\nline2\ttabbed"));

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    wireMock.verify(postRequestedFor(urlEqualTo("/v1/authentication-hmac"))
        .withHeader("X-Signature", matching(".+")));
    awaitHistorySaved(1);

    // Recompute HMAC-SHA256 over the exact bytes WireMock received, using the same secret,
    // and assert it matches the signature header that was actually sent. This is the check
    // that would have caught the earlier bug where the signed string diverged from the wire body.
    LoggedRequest received = findLoggedRequest("/v1/authentication-hmac");
    String actualSignature = received.getHeader("X-Signature");
    String expectedSignature = computeHmacSha256(hmacSecret, received.getBodyAsString());
    assertThat(actualSignature).isEqualTo(expectedSignature);
  }

  @Test
  @DisplayName("Auth RSA — X-RSA-Signature cryptographically verifies against the exact body WireMock received")
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

    // See the HMAC test above for why this body (not a flat {"amount":100}) is what actually
    // exercises the divergence between the signed string and the transmitted body.
    JsonNode root = executeGatewayWithBody("authentication-rsa", Map.of("note", "line1\nline2\ttabbed"));

    assertThat(root.path("data").path("status").asInt()).isEqualTo(200);
    wireMock.verify(postRequestedFor(urlEqualTo("/v1/authentication-rsa"))
        .withHeader("X-RSA-Signature", matching(".+")));
    awaitHistorySaved(1);

    // Verify the signature against the exact bytes WireMock received, using the matching public
    // key — a true cryptographic check, not just "a header exists". This is the check that would
    // have caught the earlier bug where the signed string diverged from the wire body.
    LoggedRequest received = findLoggedRequest("/v1/authentication-rsa");
    String actualSignature = received.getHeader("X-RSA-Signature");
    assertThat(verifyRsaSignature(received.getBody(), actualSignature)).isTrue();
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
        .outboundSystemId(system.getId()).readTimeOutMs(10000)
        .circuitBreakerEnabled(false).bulkheadEnabled(false)
        .rateLimiterEnabled(false).retryEnabled(false).build()).block();

    return system;
  }

  private JsonNode executeGateway(String code) throws Exception {
    return executeGatewayWithBody(code, Map.of("amount", 100));
  }

  private JsonNode executeGatewayWithBody(String code, Object requestBody) throws Exception {
    GatewayRequest req = new GatewayRequest(Map.of(), requestBody);
    byte[] body = webTestClient.post()
        .uri("/api/gateway/execute/" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(req)
        .exchange()
        .expectStatus().isOk()
        .expectBody().returnResult().getResponseBody();
    return objectMapper.readTree(body);
  }

  private LoggedRequest findLoggedRequest(String path) {
    return wireMock.findAll(postRequestedFor(urlEqualTo(path))).stream()
        .findFirst()
        .orElseThrow(() -> new AssertionError("No request recorded for " + path));
  }

  private String computeHmacSha256(String secret, String data) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
  }

  private boolean verifyRsaSignature(byte[] signedData, String base64Signature) throws Exception {
    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(rsaKeyPair.getPublic());
    verifier.update(signedData);
    return verifier.verify(Base64.getDecoder().decode(base64Signature));
  }

  private void awaitHistorySaved(long expected) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(3))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> historyRepository.count().block() >= expected);
  }
}
