package com.nantaaditya.example.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
import com.nantaaditya.example.cache.JsltResponseMapper;
import com.nantaaditya.example.crypto.EncryptionHelper;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.factory.AuthStrategyFactory;
import com.nantaaditya.example.helper.GatewayHelper;
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
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.service.AuthStrategy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * An {@link AuthStrategy} sees the prepared request (method, path, query, headers, body) and may return several
 * credentials. Real WebClient against WireMock; only the factory and the signer are test doubles.
 */
class OutboundRequestExecutorSigningTest {

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
  private final AtomicReference<SigningInput> seen = new AtomicReference<>();

  private OutboundRequestExecutor executor;

  @BeforeEach
  void setUp() {
    executor = new OutboundRequestExecutor(new ParameterInjector(objectMapper), jsonHelper, new Gson(),
        authStrategyFactory, mock(EncryptionHelper.class),
        new GatewayHelper(jsonHelper, mock(JsltResponseMapper.class)));
    wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse()
        .withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
  }

  @AfterEach
  void reset() {
    wireMock.resetAll();
  }

  @Test
  @DisplayName("the signer sees method, path, sorted query, headers and the exact body, injected values included")
  void execute_signerReceivesThePreparedRequest() {
    signsWith(input -> {
      seen.set(input);
      return Mono.just(List.of());
    });
    ResolvedParameters parameters = new ResolvedParameters(List.of(
        new ResolvedParameter("partner", ParameterInjectLocation.HEADER, "X-Partner-Id", "P-123"),
        new ResolvedParameter("region", ParameterInjectLocation.QUERY_PARAM, "region", "id")));

    executor.execute(context(), new GatewayRequest(Map.of("b", "2", "a", "1"), Map.of("amount", 100)),
        parameters, () -> { }).block();

    SigningInput input = seen.get();
    assertThat(input.method()).isEqualTo("POST");
    assertThat(input.path()).isEqualTo(PATH);
    assertThat(input.query()).containsExactly(
        Map.entry("a", List.of("1")), Map.entry("b", List.of("2")), Map.entry("region", List.of("id")));
    assertThat(input.headers().get("X-Configured")).containsExactly("cfg");
    assertThat(input.headers().get("x-partner-id")).containsExactly("P-123");   // case-insensitive lookup
    assertThat(input.body()).isEqualTo(sent().getBodyAsString());
  }

  @Test
  @DisplayName("a signer may return several credentials and every one of them is sent")
  void execute_severalCredentials_allSent() {
    signsWith(input -> Mono.just(List.of(
        new AuthCredential("X-Timestamp", "2026-10-26T08:00:00Z", TokenInjectLocation.HEADER),
        new AuthCredential("X-Signature", "sig-1", TokenInjectLocation.HEADER),
        new AuthCredential("token", "t-1", TokenInjectLocation.QUERY_PARAM))));

    executor.execute(context(), new GatewayRequest(Map.of(), Map.of("a", 1)), ResolvedParameters.empty(), () -> { })
        .block();

    LoggedRequest sent = sent();
    assertThat(sent.getHeader("X-Timestamp")).isEqualTo("2026-10-26T08:00:00Z");
    assertThat(sent.getHeader("X-Signature")).isEqualTo("sig-1");
    assertThat(sent.getQueryParams().get("token").firstValue()).isEqualTo("t-1");
  }

  @Test
  @DisplayName("a signing failure reaches the caller as a Mono error and nothing is sent")
  void execute_signerFails_nothingSent() {
    signsWith(input -> Mono.error(new IllegalArgumentException("signing failed")));

    StepVerifier.create(executor.execute(context(), new GatewayRequest(Map.of(), Map.of("a", 1)),
            ResolvedParameters.empty(), () -> { }))
        .expectError(IllegalArgumentException.class)
        .verify();

    assertThat(wireMock.findAll(postRequestedFor(urlPathEqualTo(PATH)))).isEmpty();
  }

  // --- helpers ---

  private void signsWith(Function<SigningInput, Mono<List<AuthCredential>>> signer) {
    AuthStrategy strategy = new AuthStrategy() {
      @Override
      public AuthType supports() {
        return AuthType.HMAC;
      }

      @Override
      public Mono<List<AuthCredential>> authenticate(SigningInput input, OutboundAuthConfiguration config,
          EncryptionHelper encryption) {
        return signer.apply(input);
      }
    };
    when(authStrategyFactory.getStrategy(any())).thenReturn(strategy);
  }

  private LoggedRequest sent() {
    List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlPathEqualTo(PATH)));
    assertThat(requests).hasSize(1);
    return requests.get(0);
  }

  private OutboundSystemContext context() {
    return OutboundSystemContext.builder()
        .system(OutboundSystem.builder().id(1L).code("pay").httpMethod("POST").apiPath(PATH)
            .hostConfigurationId(1L).build())
        .hostConfig(OutboundHostConfiguration.builder().id(1L).baseUrl(wireMock.baseUrl()).build())
        .resilience(OutboundResilienceConfiguration.builder().readTimeOutMs(1000).build())
        .auth(OutboundAuthConfiguration.builder().authType(AuthType.HMAC).build())
        .responseMapper(OutboundResponseMapperConfiguration.empty(1L))
        .headers(List.of(OutboundHeaderConfiguration.from(1L, Map.of("X-Configured", List.of("cfg")))))
        .webClient(WebClient.create(wireMock.baseUrl()))
        .build();
  }
}
