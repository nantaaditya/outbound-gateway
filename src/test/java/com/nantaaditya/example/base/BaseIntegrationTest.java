package com.nantaaditya.example.base;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nantaaditya.example.SingleModuleReactiveApplication;
import com.nantaaditya.example.helper.DateTimeHelper;
import com.nantaaditya.example.helper.TsidHelper;
import com.nantaaditya.example.model.constant.HeaderConstant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient.RequestBodySpec;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Hooks;

@Slf4j
@SpringBootTest(
    classes = SingleModuleReactiveApplication.class,
    webEnvironment = WebEnvironment.DEFINED_PORT
)
@AutoConfigureWebTestClient
@AutoConfigureObservability
public class BaseIntegrationTest {

  @Autowired
  protected WebTestClient webTestClient;

  @BeforeAll
  static void setup() {
    Hooks.enableAutomaticContextPropagation();
  }

  protected <S, T> void execute(RestApiRequest<S, T> request) {
    String requestId = TsidHelper.generateStringId();

    RequestBodySpec requestBodySpec = webTestClient.method(request.httpMethod())
        .uri(uriBuilder -> uriBuilder
            .path(request.path())
            .queryParams(MultiValueMap.fromMultiValue(request.queryParameters()))
            .build()
        )
        .headers(httpHeaders -> {
          httpHeaders.put(HeaderConstant.CLIENT_ID.getHeader(), List.of("test-client"));
          httpHeaders.put(HeaderConstant.REQUEST_ID.getHeader(), List.of(requestId));
          httpHeaders.put(HeaderConstant.REQUEST_TIME.getHeader(), List.of(DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT)));
        });

    ResponseSpec responseSpec = null;
    if (request.isEligibleUsePayload() && request.payload() != null) {
      responseSpec = requestBodySpec.bodyValue(request.payload())
          .exchange()
          .expectStatus().isEqualTo(request.assertion().httpStatus());
    } else {
      responseSpec = requestBodySpec
          .exchange()
          .expectStatus().isEqualTo(request.assertion().httpStatus());
    }

    assertHeaders(responseSpec, request);
    assertResponse(responseSpec, request);
    delayTest(request);
  }

  private <S, T> void assertHeaders(ResponseSpec responseSpec, RestApiRequest<S, T> request) {
    if (request.assertion().headers() == null) return;

    for (Map.Entry<String, String> headers : request.assertion().headers().entrySet()) {
      responseSpec.expectHeader().value(headers.getKey(), Matchers.equalTo(headers.getValue()));
    }
  }

  private void assertResponse(ResponseSpec responseSpec, RestApiRequest request) {
    if (request.assertion().response() == null) return;

    byte [] body = responseSpec.expectBody().returnResult().getResponseBody();
    assertEquals(toResponse(body), request.assertion().response());
  }

  private void delayTest(RestApiRequest request) {
    if (request.delay() == null) return;

    Awaitility.await()
        .alias(request.httpMethod() + " " + request.path())
        .timeout(request.delay().duration())
        .pollDelay(request.delay().duration())
        .until(() -> true);
  }

  private String toResponse(byte [] body) {
    try {
      return new String(body);
    } catch (Exception e) {
      log.error("#TEST - failed to parse response {}", e.getMessage());
      return null;
    }
  }

}
