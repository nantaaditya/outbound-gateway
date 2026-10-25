package com.nantaaditya.example.observability;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.DeadLetterProcess;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.repository.DeadLetterProcessRepository;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.GatewayService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Prometheus scrape contract for the central observability stack.
 *
 * <p>This gateway is a microservice: it does not ship dashboards or alert rules, but it
 * <em>must</em> expose a stable, documented metric surface. This test scrapes the real
 * {@code /actuator/prometheus} endpoint (separate management port) and asserts the exact
 * series the central stack builds alerts and dashboards from. Any change that removes or
 * renames a metric, or alters its labels, fails here — that is the contract.
 *
 * <p>Cardinality rule enforced by assertions: only {@code feature}, {@code host},
 * {@code responseCode}, {@code callerService} appear as labels. Nothing high-cardinality
 * (request IDs, idempotency keys) may ever become a Prometheus label.
 */
class PrometheusScrapeContractTest extends BaseIntegrationTest {

  static WireMockServer wireMock;

  @DynamicPropertySource
  static void registerObservabilityProperties(DynamicPropertyRegistry registry) {
    // Random management port so the scrape target never collides with a dev instance on
    // the default ACTUATOR_PORT=1000, and so parallel test contexts cannot clash.
    registry.add("management.server.port", () -> "0");
    // Deterministic env label for the contract assertions.
    registry.add("management.metrics.tags.env", () -> "test");
    // Keep the DLQ gauge poll fast enough for Awaitility in tests.
    registry.add("gateway.metrics.dlq.poll-interval-ms", () -> "200");
    registry.add("gateway.metrics.dlq.initial-delay-ms", () -> "200");
  }

  @Value("${local.management.port}")
  private int managementPort;

  // Mirrors the production setting in src/main/resources/application.yml — both files
  // carry the histogram config; if this ever reads false here the scrape assertions below
  // cannot be trusted either.
  @Value("${management.metrics.distribution.percentiles-histogram.api.public:false}")
  private boolean histogramEnabled;

  @Autowired private GatewayService gatewayService;
  @Autowired private OutboundSystemRepository systemRepository;
  @Autowired private OutboundHostConfigurationRepository hostConfigRepository;
  @Autowired private OutboundResilienceConfigurationRepository resilienceRepository;
  @Autowired private OutboundAuthConfigurationRepository authRepository;
  @Autowired private DeadLetterProcessRepository deadLetterRepository;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @AfterEach
  void cleanup() {
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    resilienceRepository.deleteAll().block();
    authRepository.deleteAll().block();
    deadLetterRepository.deleteAll().block();
  }

  @Test
  @DisplayName("api.public timer is scraped with feature, responseCode, callerService and env labels")
  void scrape_apiPublicTimer_hasCountWithExpectedLabels() {
    wireMock.stubFor(post(urlEqualTo("/v1/scrape-baseline"))
        .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

    String code = "gw-scrape-baseline";
    setupSystem(code, "http://localhost:" + wireMock.port(), "/v1/scrape-baseline");

    gatewayService.execute(code, "test-caller", null, new GatewayRequest(Map.of(), Map.of())).block();

    String body = scrapePrometheus();
    String line = findLine(body, "api_public_seconds_count", Map.of(
        "feature", code,
        "responseCode", "200",
        "callerService", "test-caller",
        "env", "test"
    ));
    assertThat(line).isNotNull();
    assertThat(valueOf(line)).isGreaterThanOrEqualTo(1.0);
  }

  @Test
  @DisplayName("api.public timer exposes histogram buckets for p95/p99 alerting")
  void scrape_apiPublicTimer_exposesHistogramBuckets() {
    wireMock.stubFor(post(urlEqualTo("/v1/scrape-histogram"))
        .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

    String code = "gw-scrape-histogram";
    setupSystem(code, "http://localhost:" + wireMock.port(), "/v1/scrape-histogram");

    gatewayService.execute(code, "test-caller", null, new GatewayRequest(Map.of(), Map.of())).block();

    assertThat(histogramEnabled)
        .as("histogram config must be enabled in the test context (mirror of main application.yml)")
        .isTrue();

    String body = scrapePrometheus();
    assertThat(hasFamily(body, "api_public_seconds_bucket"))
        .as("percentiles-histogram must be enabled for api.public — p95/p99 alerting depends on buckets")
        .isTrue();
  }

  @Test
  @DisplayName("DLQ depth gauge is scraped per process_type and process_name")
  void scrape_dlqDepthGauge_exposesUnprocessedCount() {
    deadLetterRepository.save(DeadLetterProcess.builder()
        .createdBy("test")
        .updatedBy("test")
        .processType("test-process-type")
        .processName("test-process-name")
        .lastError("contract-test")
        .payload("{}".getBytes(StandardCharsets.UTF_8))
        .processed(false)
        .build()).block();

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> hasFamily(scrapePrometheus(), "outbox_dlq_depth"));

    String line = findLine(scrapePrometheus(), "outbox_dlq_depth", Map.of(
        "process_type", "test-process-type",
        "process_name", "test-process-name"
    ));
    assertThat(line).isNotNull();
    assertThat(valueOf(line)).isGreaterThanOrEqualTo(1.0);
  }

  @Test
  @DisplayName("connection pool metrics are scraped per host for capacity alerting")
  void scrape_connectionPool_exposesTotalConnections() {
    wireMock.stubFor(post(urlEqualTo("/v1/scrape-pool"))
        .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

    String code = "gw-scrape-pool";
    setupSystem(code, "http://localhost:" + wireMock.port(), "/v1/scrape-pool");

    gatewayService.execute(code, "test-caller", null, new GatewayRequest(Map.of(), Map.of())).block();

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> hasFamily(scrapePrometheus(), "reactor_netty_connection_provider_total_connections"));
  }

  @Test
  @DisplayName("api.public timer carries host label to correlate system with connection pool")
  void scrape_apiPublicTimer_includesHostLabel() {
    wireMock.stubFor(post(urlEqualTo("/v1/scrape-host"))
        .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

    String code = "gw-scrape-host";
    setupSystem(code, "http://localhost:" + wireMock.port(), "/v1/scrape-host");

    gatewayService.execute(code, "test-caller", null, new GatewayRequest(Map.of(), Map.of())).block();

    String line = findLine(scrapePrometheus(), "api_public_seconds_count", Map.of("feature", code));
    assertThat(line).isNotNull();
    assertThat(line).contains("host=\"");
  }

  @Test
  @DisplayName("cache metrics: Caffeine series per cache name, plus entry gauges for the two map caches")
  void scrape_cacheMetrics_present() {
    wireMock.stubFor(post(urlEqualTo("/v1/scrape-cache"))
        .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
    String code = "gw-scrape-cache";
    setupSystem(code, "http://localhost:" + wireMock.port(), "/v1/scrape-cache");

    gatewayService.execute(code, "test-caller", null, new GatewayRequest(Map.of(), Map.of())).block();

    String body = scrapePrometheus();
    for (String cache : new String[] {"jslt", "parameters", "contexts", "additional-data"}) {
      assertThat(findLine(body, "cache_size", Map.of("cache", cache))).as("cache_size for %s", cache).isNotNull();
      assertThat(findLine(body, "cache_gets_total", Map.of("cache", cache, "result", "miss")))
          .as("cache_gets_total miss for %s", cache).isNotNull();
    }
    assertThat(valueOf(findLine(body, "gateway_context_cache_entries", Map.of()))).isGreaterThanOrEqualTo(1.0);
    assertThat(valueOf(findLine(body, "gateway_webclient_cache_entries", Map.of()))).isGreaterThanOrEqualTo(1.0);
  }

  private String scrapePrometheus() {
    byte[] body = WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + managementPort)
        .build()
        .get()
        .uri("/actuator/prometheus")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .returnResult()
        .getResponseBody();
    return new String(body, StandardCharsets.UTF_8);
  }

  private static String findLine(String body, String family, Map<String, String> requiredLabels) {
    return body.lines()
        .filter(line -> isSampleLine(line, family))
        .filter(line -> requiredLabels.entrySet().stream()
            .allMatch(e -> line.contains(e.getKey() + "=\"" + e.getValue() + "\"")))
        .findFirst()
        .orElse(null);
  }

  private static boolean hasFamily(String body, String family) {
    return body.lines().anyMatch(line -> isSampleLine(line, family));
  }

  private static boolean isSampleLine(String line, String family) {
    return line.startsWith(family + "{") || line.startsWith(family + " ");
  }

  private static double valueOf(String line) {
    return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
  }

  private void setupSystem(String code, String baseUrl, String apiPath) {
    OutboundHostConfiguration hostConfig = hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl(baseUrl)
        .connectTimeOutMs(5000)
        .maxConnections(50)
        .pendingAcquireMaxCount(50)
        .pendingAcquireTimeoutMs(5000)
        .disableSslVerification(false)
        .useProxy(false)
        .build()).block();

    OutboundSystem system = systemRepository.save(OutboundSystem.builder()
        .code(code)
        .name(code)
        .httpMethod("POST")
        .hostConfigurationId(hostConfig.getId())
        .apiPath(apiPath)
        .active(true)
        .idempotencyTtlHours(24)
        .build()).block();

    resilienceRepository.save(OutboundResilienceConfiguration.builder()
        .outboundSystemId(system.getId())
        .readTimeOutMs(10000)
        .circuitBreakerEnabled(false)
        .bulkheadEnabled(false)
        .rateLimiterEnabled(false)
        .retryEnabled(false)
        .build()).block();

    authRepository.save(OutboundAuthConfiguration.builder()
        .outboundSystemId(system.getId())
        .authType(AuthType.NONE)
        .build()).block();
  }
}
