package com.nantaaditya.example.gateway;

import com.google.gson.Gson;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.factory.AuthStrategyFactory;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.helper.GatewayHelper;
import com.nantaaditya.example.helper.GsonHelper;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.helper.ParameterInjector;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.dto.ResolvedParameters;
import com.nantaaditya.example.model.dto.SigningInput;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.response.GatewayResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Builds and sends one outbound HTTP call for a system: injects the resolved parameters, authenticates/signs
 * the body, assembles URI and headers, sends the request and maps the answer. It does not apply resilience
 * (see {@link ResilienceDecorator}) and does not record anything.
 */
@Component
@RequiredArgsConstructor
public class OutboundRequestExecutor {

  private final ParameterInjector parameterInjector;
  private final JsonHelper jsonHelper;
  private final Gson gson;
  private final AuthStrategyFactory authStrategyFactory;
  private final EncryptionHelper encryptionHelper;
  private final GatewayHelper gatewayHelper;

  /**
   * @param onAttempt invoked every time the returned {@code Mono} is subscribed. A retry re-subscribes, so
   *                  the caller counts attempts with it (retries = attempts - 1).
   */
  public Mono<GatewayResponse> execute(OutboundSystemContext context, GatewayRequest request,
      ResolvedParameters parameters, Runnable onAttempt) {
    // Deferred: building the body can fail, and that failure must reach the caller as a Mono error. Thrown
    // while the call is being assembled it would escape the caller's error-to-reject conversion and leave no
    // history row.
    return Mono.defer(() -> {
      // Signed once here and reused verbatim for the outbound call below — HMAC/RSA must sign
      // exactly the bytes that go on the wire, not a separately re-serialized copy of the body.
      // BODY parameters are injected before signing so the signature covers them; the original
      // request (without injected values) is what the history persists.
      Object outboundBody = parameterInjector.injectBody(request.body(), parameters.bodyItems());
      String cleanedBody = GsonHelper.cleanJson(jsonHelper.toJsonString(outboundBody), gson);
      HttpHeaders headers = prepareHeaders(context, parameters);
      SigningInput signingInput = signingInput(context, request, parameters, headers, cleanedBody);
      return authenticateRequest(context, signingInput)
          .doOnSubscribe(__ -> onAttempt.run())
          .flatMap(credentials -> executeHttp(context, request, parameters, cleanedBody, headers, credentials))
          .map(response -> gatewayHelper.applyMapping(context, response));
    });
  }

  // Configured headers first, then injected ones (which replace a configured header of the same name). Built
  // before authentication so a signer sees them; the credentials are added to the request afterwards.
  private HttpHeaders prepareHeaders(OutboundSystemContext context, ResolvedParameters parameters) {
    HttpHeaders headers = new HttpHeaders();
    gatewayHelper.applyConfiguredHeaders(context, headers);
    parameters.headers().forEach(headers::set);
    return headers;
  }

  private SigningInput signingInput(OutboundSystemContext context, GatewayRequest request,
      ResolvedParameters parameters, HttpHeaders headers, String cleanedBody) {
    Map<String, List<String>> query = new HashMap<>();
    if (request.hasQueryParams()) {
      request.queryParams().forEach((name, value) -> query.computeIfAbsent(name, n -> new ArrayList<>()).add(value));
    }
    parameters.queryParams().forEach((name, value) -> query.computeIfAbsent(name, n -> new ArrayList<>()).add(value));
    OutboundSystem system = context.getSystem();
    return new SigningInput(system.getHttpMethodEnum().name(), system.getApiPath(), query, headers, cleanedBody);
  }

  // Every strategy sees the whole prepared request and returns the credentials to attach (none, one or several).
  private Mono<List<AuthCredential>> authenticateRequest(OutboundSystemContext context, SigningInput input) {
    return authStrategyFactory
        .getStrategy(context.getAuth().getAuthType())
        .authenticate(input, context.getAuth(), encryptionHelper);
  }

  private Mono<GatewayResponse> executeHttp(OutboundSystemContext context, GatewayRequest request,
      ResolvedParameters parameters, String cleanedBody, HttpHeaders preparedHeaders,
      List<AuthCredential> credentials) {
    OutboundSystem system = context.getSystem();
    HttpMethod httpMethod = system.getHttpMethodEnum();

    WebClient.RequestBodySpec requestSpec = context.getWebClient()
        .method(httpMethod)
        .uri(uriBuilder -> {
          uriBuilder.path(system.getApiPath());
          if (request.hasQueryParams()) {
            request.queryParams().forEach(uriBuilder::queryParam);
          }
          // Injected values go through URI template variables so they are encoded as ONE value:
          // a literal "{x}" is not expanded and "&" / "+" cannot smuggle extra query params.
          Map<String, Object> uriVariables = new HashMap<>();
          parameters.queryParams().forEach((name, value) -> {
            String variable = "injected" + uriVariables.size();
            uriVariables.put(variable, value);
            uriBuilder.queryParam(name, "{" + variable + "}");
          });
          credentials.stream()
              .filter(credential -> !credential.isEmpty() && credential.location() == TokenInjectLocation.QUERY_PARAM)
              .forEach(credential -> uriBuilder.queryParam(credential.keyName(), credential.value()));
          return uriBuilder.build(uriVariables);
        })
        .headers(headers -> {
          headers.addAll(preparedHeaders);
          credentials.forEach(credential -> gatewayHelper.applyAuthHeader(credential, headers));
        });

    // Send the same cleanedBody string that was signed above — not request.body() re-serialized
    // independently by WebFlux's encoder, which would silently break HMAC/RSA verification.
    Mono<GatewayResponse> httpMono = (request.hasBody()
        ? requestSpec.bodyValue(cleanedBody)
        : requestSpec
    ).exchangeToMono(response ->
        response.bodyToMono(String.class)
            .defaultIfEmpty("")
            .map(body -> GatewayResponse.passThrough(
                response.statusCode().value(),
                gatewayHelper.collectResponseHeaders(response),
                body
            ))
    );
    // Skip timeout when resilience is bypassed (readTimeOutMs == 0).
    int readTimeoutMs = context.getResilience().getReadTimeOutMs();
    return readTimeoutMs > 0 ? httpMono.timeout(Duration.ofMillis(readTimeoutMs)) : httpMono;
  }
}
