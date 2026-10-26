package com.nantaaditya.example.service.strategy.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The token flow against a real HTTP token endpoint (WireMock): what is sent, how the token is found in the answer,
 * when it is cached and when it is fetched again.
 */
class OAuthClientCredentialsAuthStrategyTest {

  private static final String TOKEN_PATH = "/oauth/token";
  private static final long CONFIG_ID = 7L;
  private static final long SYSTEM_ID = 1L;

  private static WireMockServer wireMock;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  private final EncryptionHelper encryption = new EncryptionHelper(Base64.getEncoder().encodeToString(new byte[32]));
  private final OutboundAuthConfigurationRepository repository = mock(OutboundAuthConfigurationRepository.class);

  private OAuthClientCredentialsAuthStrategy strategy;

  @BeforeEach
  void setUp() {
    strategy = new OAuthClientCredentialsAuthStrategy(WebClient.builder(), repository);
    strategy.init();
    when(repository.updateToken(eq(CONFIG_ID), eq(SYSTEM_ID), any(), any())).thenReturn(Mono.just(1));
    tokenEndpointReturns("{\"access_token\":\"tok-1\",\"expires_in\":3600}");
  }

  @AfterEach
  void reset() {
    wireMock.resetAll();
  }

  @Test
  @DisplayName("sends the client credentials as a form and returns the token with its prefix, key name and location")
  void authenticate_fetchesTokenWithClientCredentials() {
    List<AuthCredential> credentials = authenticate(config(false, "access_token", null));

    assertThat(credentials).containsExactly(new AuthCredential("Authorization", "Bearer tok-1", TokenInjectLocation.HEADER));
    wireMock.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH))
        .withRequestBody(containing("grant_type=client_credentials"))
        .withRequestBody(containing("client_id=cid"))
        .withRequestBody(containing("client_secret=csecret")));
  }

  @Test
  @DisplayName("a JSONPath expression finds the token in a nested answer")
  void authenticate_jsonPathExtraction() {
    tokenEndpointReturns("{\"data\":{\"access_token\":\"nested-tok\"}}");

    List<AuthCredential> credentials = authenticate(config(false, "$.data.access_token", null));

    assertThat(credentials.get(0).value()).isEqualTo("Bearer nested-tok");
  }

  @Test
  @DisplayName("an answer without the token field fails and names the missing field")
  void authenticate_missingTokenField_fails() {
    tokenEndpointReturns("{\"other\":\"x\"}");

    StepVerifier.create(strategy.authenticate(AuthTestSupport.bodyOnly(""), config(false, "access_token", null), encryption))
        .expectErrorMatches(e -> e.getMessage().contains("missing field: access_token"))
        .verify(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("a JSONPath that does not exist in the answer is treated as a missing token")
  void authenticate_jsonPathNotFound_fails() {
    StepVerifier.create(strategy.authenticate(AuthTestSupport.bodyOnly(""), config(false, "$.nope.token", null), encryption))
        .expectError(RuntimeException.class)
        .verify(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("without auto refresh nothing is cached or stored: every call fetches a new token")
  void authenticate_noAutoRefresh_fetchesEveryTime() {
    OutboundAuthConfiguration config = config(false, "access_token", null);

    authenticate(config);
    authenticate(config);

    wireMock.verify(2, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    verify(repository, never()).updateToken(any(), anyLong(), any(), any());
  }

  @Test
  @DisplayName("with auto refresh and an expiry the token is cached and stored, so the next call does not fetch")
  void authenticate_autoRefreshWithExpiry_cachesAndPersists() {
    OutboundAuthConfiguration config = config(true, "access_token", "expires_in");

    authenticate(config);
    List<AuthCredential> second = authenticate(config);

    assertThat(second.get(0).value()).isEqualTo("Bearer tok-1");
    wireMock.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    verify(repository).updateToken(eq(CONFIG_ID), eq(SYSTEM_ID), eq("tok-1"), any(LocalDateTime.class));
  }

  @Test
  @DisplayName("evicting the token state forces the next call to fetch again")
  void evictTokenState_forcesRefetch() {
    OutboundAuthConfiguration config = config(true, "access_token", "expires_in");
    authenticate(config);

    strategy.evictTokenState(CONFIG_ID);
    authenticate(config);

    wireMock.verify(2, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
  }

  @Test
  @DisplayName("auto refresh without an expiry path cannot cache: it fetches every time")
  void authenticate_autoRefreshWithoutExpiryPath_fetchesEveryTime() {
    OutboundAuthConfiguration config = config(true, "access_token", null);

    authenticate(config);
    authenticate(config);

    wireMock.verify(2, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
  }

  @Test
  @DisplayName("an expiry that is not a number is ignored and the token is fetched every time")
  void authenticate_unparseableExpiry_fetchesEveryTime() {
    tokenEndpointReturns("{\"access_token\":\"tok-1\",\"expires_in\":\"soon\"}");
    OutboundAuthConfiguration config = config(true, "access_token", "expires_in");

    authenticate(config);
    authenticate(config);

    wireMock.verify(2, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
  }

  @Test
  @DisplayName("a token that cannot be stored is still returned to the caller")
  void authenticate_persistFails_tokenStillReturned() {
    when(repository.updateToken(eq(CONFIG_ID), eq(SYSTEM_ID), any(), any()))
        .thenReturn(Mono.error(new IllegalStateException("db down")));

    List<AuthCredential> credentials = authenticate(config(true, "access_token", "expires_in"));

    assertThat(credentials.get(0).value()).isEqualTo("Bearer tok-1");
  }

  @Test
  @DisplayName("a still-valid token stored on the configuration is used without calling the token endpoint")
  void authenticate_storedValidToken_noFetch() {
    OutboundAuthConfiguration config = configBuilder(true, "access_token", "expires_in")
        .currentToken("stored-tok")
        .tokenExpiryTime(LocalDateTime.now().plusMinutes(10))
        .build();

    List<AuthCredential> credentials = authenticate(config);

    assertThat(credentials.get(0).value()).isEqualTo("Bearer stored-tok");
    wireMock.verify(0, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
  }

  @Test
  @DisplayName("concurrent callers share one token request")
  void authenticate_concurrentCallers_oneFetch() {
    wireMock.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(aResponse()
        .withStatus(200).withHeader("Content-Type", "application/json")
        .withBody("{\"access_token\":\"tok-1\",\"expires_in\":3600}").withFixedDelay(400)));
    OutboundAuthConfiguration config = config(true, "access_token", "expires_in");

    List<List<AuthCredential>> results = Flux.range(0, 5)
        .flatMap(i -> strategy.authenticate(AuthTestSupport.bodyOnly(""), config, encryption), 5)
        .collectList()
        .block(Duration.ofSeconds(10));

    assertThat(results).hasSize(5).allSatisfy(c -> assertThat(c.get(0).value()).isEqualTo("Bearer tok-1"));
    wireMock.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
  }

  @Test
  @DisplayName("the token is injected into a query parameter when that location is configured")
  void authenticate_queryParamLocation() {
    OutboundAuthConfiguration config = configBuilder(false, "access_token", null)
        .tokenInjectLocation(TokenInjectLocation.QUERY_PARAM)
        .tokenKeyName("access_token")
        .tokenPrefix(null)
        .build();

    List<AuthCredential> credentials = authenticate(config);

    assertThat(credentials).containsExactly(new AuthCredential("access_token", "tok-1", TokenInjectLocation.QUERY_PARAM));
  }

  @Test
  @DisplayName("supports OAUTH")
  void supports_oauth() {
    assertThat(strategy.supports()).isEqualTo(AuthType.OAUTH);
  }

  // --- helpers ---

  private List<AuthCredential> authenticate(OutboundAuthConfiguration config) {
    return strategy.authenticate(AuthTestSupport.bodyOnly(""), config, encryption).block(Duration.ofSeconds(5));
  }

  private void tokenEndpointReturns(String body) {
    wireMock.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(aResponse()
        .withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
  }

  private OutboundAuthConfiguration config(boolean autoRefresh, String tokenPath, String expiryPath) {
    return configBuilder(autoRefresh, tokenPath, expiryPath).build();
  }

  private OutboundAuthConfiguration.OutboundAuthConfigurationBuilder<?, ?> configBuilder(
      boolean autoRefresh, String tokenPath, String expiryPath) {
    return OutboundAuthConfiguration.builder()
        .id(CONFIG_ID)
        .outboundSystemId(SYSTEM_ID)
        .authType(AuthType.OAUTH)
        .clientId(encryption.encryptString("cid"))
        .clientSecret(encryption.encryptString("csecret"))
        .autoRefresh(autoRefresh)
        .tokenMethod("POST")
        .tokenUrl(wireMock.baseUrl() + TOKEN_PATH)
        .tokenPrefix("Bearer")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .tokenKeyName("Authorization")
        .tokenResponsePath(tokenPath)
        .tokenResponseExpiryPath(expiryPath);
  }
}
