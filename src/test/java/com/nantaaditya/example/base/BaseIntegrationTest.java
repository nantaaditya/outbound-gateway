package com.nantaaditya.example.base;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nantaaditya.example.OutboxGatewayApplication;
import com.nantaaditya.example.helper.DateTimeHelper;
import com.nantaaditya.example.helper.TsidHelper;
import com.nantaaditya.example.model.constant.HeaderConstant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.log4j.Log4j2;
import org.awaitility.Awaitility;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient.RequestBodySpec;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Hooks;
import reactor.core.scheduler.Schedulers;

@Log4j2
@SpringBootTest(
    classes = OutboxGatewayApplication.class,
    webEnvironment = WebEnvironment.RANDOM_PORT
)
@AutoConfigureWebTestClient
@AutoConfigureMetrics
@AutoConfigureTracing
public class BaseIntegrationTest { //NOSONAR

  // Singleton: started once when this class is loaded, reused by all test classes via Spring context cache.
  // @Container/@Testcontainers are intentionally omitted — they stop/restart the container between classes,
  // breaking the Spring context cache (each restart gets a different port).
  private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    postgres.start();
  }

  @DynamicPropertySource
  static void registerContainerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.r2dbc.url", () ->
        "r2dbc:postgresql://%s:%d/%s".formatted(
            postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName()
        ));
    registry.add("spring.r2dbc.username", postgres::getUsername);
    registry.add("spring.r2dbc.password", postgres::getPassword);
    registry.add("spring.flyway.url", postgres::getJdbcUrl);
    registry.add("spring.flyway.user", postgres::getUsername);
    registry.add("spring.flyway.password", postgres::getPassword);
  }

  @Autowired
  protected WebTestClient webTestClient;

  @BeforeAll
  static void setup() {
    Hooks.enableAutomaticContextPropagation();
    Hooks.onErrorDropped(e -> log.warn("#TEST - dropped error: {}", e.getMessage()));
    Schedulers.onHandleError((thread, error) ->
        log.warn("#TEST - scheduler error on {}: {}", thread.getName(), error.getMessage(), error));
  }

  @BeforeEach
  void injectMandatoryHeaders() {
    webTestClient = webTestClient.mutate()
        .defaultHeader(HeaderConstant.CLIENT_ID.getHeader(), "test-client")
        .defaultHeader(HeaderConstant.REQUEST_ID.getHeader(), TsidHelper.generateStringId())
        .defaultHeader(HeaderConstant.REQUEST_TIME.getHeader(),
            DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .build();
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
