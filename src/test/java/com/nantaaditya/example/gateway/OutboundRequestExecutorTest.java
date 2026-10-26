package com.nantaaditya.example.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.google.gson.Gson;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.factory.AuthStrategyFactory;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.helper.GatewayHelper;
import com.nantaaditya.example.helper.JsltResponseMapper;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.helper.ParameterInjector;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ParameterInjectLocation;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.dto.ResolvedParameter;
import com.nantaaditya.example.model.dto.ResolvedParameters;
import com.nantaaditya.example.model.dto.SigningInput;
import com.nantaaditya.example.model.exception.InvalidParameterException;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.response.GatewayResponse;
import com.nantaaditya.example.service.AuthStrategy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Real WebClient against WireMock, real JSON/Gson/parameter helpers; only the auth strategy is a mock. What is
 * asserted is what actually arrives on the wire.
 */
class OutboundRequestExecutorTest {

  private static final String PATH = "/v1/pay";

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

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final JsonHelper jsonHelper = new JsonHelper(objectMapper);
  private final AuthStrategyFactory authStrategyFactory = mock(AuthStrategyFactory.class);
  private final AuthStrategy authStrategy = mock(AuthStrategy.class);
  private final EncryptionHelper encryptionHelper = mock(EncryptionHelper.class);

  private OutboundRequestExecutor executor;

  @BeforeEach
  void setUp() {
    executor = new OutboundRequestExecutor(new ParameterInjector(objectMapper), jsonHelper, new Gson(),
        authStrategyFactory, encryptionHelper,
        new GatewayHelper(jsonHelper, mock(JsltResponseMapper.class)));
    when(authStrategyFactory.getStrategy(any())).thenReturn(authStrategy);
    authenticatesWith(AuthCredential.empty());
    wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse()
        .withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
  }

  @AfterEach
  void reset() {
    wireMock.resetAll();
  }

  @Test
  @DisplayName("sends the system's method and path with the request body as compact JSON")
  void execute_sendsMethodPathAndBody() {
    GatewayResponse response = executor
        .execute(context(1000), new GatewayRequest(Map.of(), Map.of("amount", 100)), ResolvedParameters.empty(), () -> { })
        .block();

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"ok\":true}");
    assertThat(sent().getBodyAsString()).isEqualTo("{\"amount\":100}");
  }

  @Test
  @DisplayName("the authentication strategy signs exactly the bytes that go on the wire")
  void execute_authenticatesTheExactBodySent() {
    ArgumentCaptor<SigningInput> signedInput = ArgumentCaptor.forClass(SigningInput.class);
    when(authStrategy.authenticate(signedInput.capture(), any(), any())).thenReturn(Mono.just(List.of()));

    executor.execute(context(1000), new GatewayRequest(Map.of(), Map.of("amount", 100)),
        ResolvedParameters.empty(), () -> { }).block();

    assertThat(signedInput.getValue().body()).isEqualTo(sent().getBodyAsString());
  }

  @Test
  @DisplayName("a BODY parameter is injected before signing, so the signature covers it and it reaches the upstream")
  void execute_bodyParameter_injectedBeforeSigning() {
    ArgumentCaptor<SigningInput> signedInput = ArgumentCaptor.forClass(SigningInput.class);
    when(authStrategy.authenticate(signedInput.capture(), any(), any())).thenReturn(Mono.just(List.of()));
    ResolvedParameters parameters = new ResolvedParameters(List.of(
        new ResolvedParameter("merchant", ParameterInjectLocation.BODY, "meta.merchantId", "M-9")));

    executor.execute(context(1000), new GatewayRequest(Map.of(), Map.of("amount", 100)), parameters, () -> { })
        .block();

    assertThat(sent().getBodyAsString()).contains("\"meta\":{\"merchantId\":\"M-9\"}").contains("\"amount\":100");
    assertThat(signedInput.getValue().body()).isEqualTo(sent().getBodyAsString());
  }

  @Test
  @DisplayName("configured headers, injected headers and the authentication header are all sent")
  void execute_sendsConfiguredInjectedAndAuthHeaders() {
    authenticatesWith(new AuthCredential("X-Signature", "sig-1", TokenInjectLocation.HEADER));
    ResolvedParameters parameters = new ResolvedParameters(List.of(
        new ResolvedParameter("partner", ParameterInjectLocation.HEADER, "X-Partner-Id", "P-123")));

    executor.execute(contextWithHeaders(1000), new GatewayRequest(Map.of(), Map.of("a", 1)), parameters, () -> { })
        .block();

    LoggedRequest sent = sent();
    assertThat(sent.getHeader("X-Configured")).isEqualTo("cfg");
    assertThat(sent.getHeader("X-Partner-Id")).isEqualTo("P-123");
    assertThat(sent.getHeader("X-Signature")).isEqualTo("sig-1");
  }

  @Test
  @DisplayName("client query params, injected query params (one encoded value each) and an authentication query token are sent")
  void execute_sendsQueryParams() {
    authenticatesWith(new AuthCredential("token", "t-1", TokenInjectLocation.QUERY_PARAM));
    ResolvedParameters parameters = new ResolvedParameters(List.of(
        new ResolvedParameter("partner", ParameterInjectLocation.QUERY_PARAM, "partner", "x&y=2")));

    executor.execute(context(1000), new GatewayRequest(Map.of("a", "1"), Map.of("k", "v")), parameters, () -> { })
        .block();

    wireMock.verify(postRequestedFor(urlPathEqualTo(PATH))
        .withQueryParam("a", equalTo("1"))
        .withQueryParam("partner", equalTo("x&y=2"))      // "&" inside the value did not start a new param
        .withQueryParam("token", equalTo("t-1")));
    assertThat(sent().getQueryParams()).doesNotContainKey("y");
  }

  @Test
  @DisplayName("a request without a body sends no body and signs an empty string")
  void execute_noBody_sendsNoBody() {
    ArgumentCaptor<SigningInput> signedInput = ArgumentCaptor.forClass(SigningInput.class);
    when(authStrategy.authenticate(signedInput.capture(), any(), any())).thenReturn(Mono.just(List.of()));

    executor.execute(context(1000), new GatewayRequest(Map.of(), null), ResolvedParameters.empty(), () -> { })
        .block();

    assertThat(sent().getBodyAsString()).isEmpty();
    assertThat(signedInput.getValue().body()).isEmpty();
  }

  @Test
  @DisplayName("the attempt callback fires once per subscription, which is what counts retries")
  void execute_onAttempt_firesOncePerSubscription() {
    AtomicInteger attempts = new AtomicInteger();
    Mono<GatewayResponse> call = executor.execute(context(1000),
        new GatewayRequest(Map.of(), Map.of("a", 1)), ResolvedParameters.empty(), attempts::incrementAndGet);

    call.block();
    call.block();

    assertThat(attempts).hasValue(2);
  }

  @Test
  @DisplayName("an upstream status, headers and body are returned as they are, errors included")
  void execute_returnsUpstreamStatusHeadersAndBody() {
    wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse()
        .withStatus(503).withHeader("X-Upstream", "u-1").withBody("{\"error\":\"down\"}")));

    GatewayResponse response = executor
        .execute(context(1000), new GatewayRequest(Map.of(), Map.of("a", 1)), ResolvedParameters.empty(), () -> { })
        .block();

    assertThat(response.status()).isEqualTo(503);
    assertThat(response.body()).isEqualTo("{\"error\":\"down\"}");
    assertThat(response.headers().get("X-Upstream")).containsExactly("u-1");
  }

  @Test
  @DisplayName("the read timeout fails the call when the upstream is slower")
  void execute_readTimeout_failsSlowUpstream() {
    wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

    StepVerifier.create(executor.execute(context(200),
            new GatewayRequest(Map.of(), Map.of("a", 1)), ResolvedParameters.empty(), () -> { }))
        .expectError(TimeoutException.class)
        .verify(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("with no read timeout configured (0) a slow upstream is waited for")
  void execute_noReadTimeout_waitsForUpstream() {
    wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(400).withBody("slow")));

    GatewayResponse response = executor
        .execute(context(0), new GatewayRequest(Map.of(), Map.of("a", 1)), ResolvedParameters.empty(), () -> { })
        .block(Duration.ofSeconds(5));

    assertThat(response.body()).isEqualTo("slow");
  }

  @Test
  @DisplayName("an invalid BODY parameter fails the returned Mono instead of throwing while the call is assembled")
  void execute_bodyParameterWithoutBody_failsTheMono() {
    ResolvedParameters parameters = new ResolvedParameters(List.of(
        new ResolvedParameter("merchant", ParameterInjectLocation.BODY, "meta.merchantId", "M-9")));

    // GatewayServiceImpl converts a Mono error into an internal reject and records it; a synchronous throw
    // from execute(...) would have escaped that conversion and left no history row.
    Mono<GatewayResponse> call =
        executor.execute(context(1000), new GatewayRequest(Map.of(), null), parameters, () -> { });

    StepVerifier.create(call).expectError(InvalidParameterException.class).verify();
  }

  // --- helpers ---

  private void authenticatesWith(AuthCredential credential) {
    when(authStrategy.authenticate(any(), any(), any()))
        .thenReturn(Mono.just(credential.isEmpty() ? List.<AuthCredential>of() : List.of(credential)));
  }

  private LoggedRequest sent() {
    List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlPathEqualTo(PATH)));
    assertThat(requests).hasSize(1);
    return requests.get(0);
  }

  private OutboundSystemContext context(int readTimeOutMs) {
    return contextBuilder(readTimeOutMs).headers(List.of()).build();
  }

  private OutboundSystemContext contextWithHeaders(int readTimeOutMs) {
    return contextBuilder(readTimeOutMs)
        .headers(List.of(OutboundHeaderConfiguration.from(1L, Map.of("X-Configured", List.of("cfg")))))
        .build();
  }

  private OutboundSystemContext.OutboundSystemContextBuilder contextBuilder(int readTimeOutMs) {
    OutboundResilienceConfiguration resilience = readTimeOutMs > 0
        ? OutboundResilienceConfiguration.builder().readTimeOutMs(readTimeOutMs).build()
        : OutboundResilienceConfiguration.bypass();
    return OutboundSystemContext.builder()
        .system(OutboundSystem.builder().id(1L).code("pay").httpMethod("POST").apiPath(PATH)
            .hostConfigurationId(1L).build())
        .hostConfig(OutboundHostConfiguration.builder().id(1L).baseUrl(wireMock.baseUrl()).build())
        .resilience(resilience)
        .auth(OutboundAuthConfiguration.builder().authType(AuthType.NONE).build())
        .responseMapper(OutboundResponseMapperConfiguration.empty(1L))
        .webClient(WebClient.create(wireMock.baseUrl()));
  }
}
