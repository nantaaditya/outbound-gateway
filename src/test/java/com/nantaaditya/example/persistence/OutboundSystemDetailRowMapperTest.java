package com.nantaaditya.example.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.response.OutboundSystemDetailResponse;
import com.nantaaditya.example.properties.LogProperties;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The admin "detail" view of a system is built from one flat row of {@code v_outbound_system_detail}. Each optional
 * section appears only when its LEFT JOIN produced an id, and header values of sensitive names never leave the service.
 */
class OutboundSystemDetailRowMapperTest {

  private final Map<String, Object> columns = new HashMap<>();
  private final LogProperties logProperties = mock(LogProperties.class);
  private OutboundSystemDetailRowMapper mapper;
  private Readable row;

  @BeforeEach
  void setUp() {
    mapper = new OutboundSystemDetailRowMapper(new JsonHelper(new ObjectMapper()), logProperties);
    lenient().when(logProperties.getSensitiveFields()).thenReturn(Set.of("authorization", "x-api-key"));
    row = mock(Readable.class);
    lenient().when(row.get(anyString(), any(Class.class))).thenAnswer(invocation -> columns.get(invocation.getArgument(0)));
    columns.put("id", 1L);
    columns.put("code", "pay");
    columns.put("name", "Pay");
    columns.put("http_method", "POST");
    columns.put("base_url", "https://h.example.com");
    columns.put("api_path", "/v1/pay");
    columns.put("is_active", true);
    columns.put("idempotency_ttl_hours", 24);
  }

  @Test
  @DisplayName("the base fields are mapped and every section whose join found nothing is absent")
  void map_baseOnly_sectionsAbsent() {
    OutboundSystemDetailResponse response = mapper.map(row);

    assertThat(response.id()).isEqualTo(1L);
    assertThat(response.code()).isEqualTo("pay");
    assertThat(response.httpMethod()).isEqualTo("POST");
    assertThat(response.baseUrl()).isEqualTo("https://h.example.com");
    assertThat(response.apiPath()).isEqualTo("/v1/pay");
    assertThat(response.active()).isTrue();
    assertThat(response.idempotencyTtlHours()).isEqualTo(24);
    assertThat(response.resilience()).isNull();
    assertThat(response.authentication()).isNull();
    assertThat(response.responseMapper()).isNull();
    assertThat(response.hostConfiguration()).isNull();
    assertThat(response.headers()).isEmpty();
  }

  @Test
  @DisplayName("an inactive system and missing numbers map to false and 0")
  void map_missingValues_defaults() {
    columns.remove("is_active");
    columns.remove("idempotency_ttl_hours");

    OutboundSystemDetailResponse response = mapper.map(row);

    assertThat(response.active()).isFalse();
    assertThat(response.idempotencyTtlHours()).isZero();
  }

  @Test
  @DisplayName("production jsonb headers are read and the sensitive ones are redacted")
  void map_jsonHeaders_sensitiveRedacted() {
    columns.put("h_headers", Json.of("{\"Authorization\":\"Bearer secret\",\"X-Trace\":\"t-1\"}"));

    Map<String, Object> headers = mapper.map(row).headers();

    assertThat(headers).containsEntry("Authorization", "***").containsEntry("X-Trace", "t-1");
    assertThat(headers.values()).doesNotContain("Bearer secret");
  }

  @Test
  @DisplayName("bytea headers (test databases) are read the same way and also redacted")
  void map_byteHeaders_sensitiveRedacted() {
    columns.put("h_headers", "{\"X-API-KEY\":\"k-123\",\"Accept\":\"json\"}".getBytes(StandardCharsets.UTF_8));

    Map<String, Object> headers = mapper.map(row).headers();

    assertThat(headers).containsEntry("X-API-KEY", "***").containsEntry("Accept", "json");
  }

  @Test
  @DisplayName("an empty header object or an unknown column type gives no headers")
  void map_emptyOrUnknownHeaders_empty() {
    columns.put("h_headers", Json.of("{}"));
    assertThat(mapper.map(row).headers()).isEmpty();

    columns.put("h_headers", "not a supported type");
    assertThat(mapper.map(row).headers()).isEmpty();
  }

  @Test
  @DisplayName("the authentication section carries no secrets, only the non-secret settings")
  void map_authentication() {
    columns.put("a_id", 5L);
    columns.put("auth_type", "OAUTH");
    columns.put("is_auto_refresh", true);
    columns.put("token_method", "POST");
    columns.put("token_url", "https://auth.example.com/token");
    columns.put("token_prefix", "Bearer");
    columns.put("token_inject_location", "HEADER");
    columns.put("token_key_name", "Authorization");
    columns.put("token_response_path", "$.access_token");
    columns.put("token_response_expiry_path", "expires_in");

    var auth = mapper.map(row).authentication();

    assertThat(auth.authType()).isEqualTo(AuthType.OAUTH);
    assertThat(auth.autoRefresh()).isTrue();
    assertThat(auth.tokenUrl()).isEqualTo("https://auth.example.com/token");
    assertThat(auth.tokenInjectLocation()).isEqualTo(TokenInjectLocation.HEADER);
    assertThat(auth.tokenKeyName()).isEqualTo("Authorization");
    assertThat(auth.tokenResponseExpiryPath()).isEqualTo("expires_in");
  }

  @Test
  @DisplayName("an authentication row without type or location maps those to null")
  void map_authenticationWithoutTypeOrLocation() {
    columns.put("a_id", 5L);

    var auth = mapper.map(row).authentication();

    assertThat(auth.authType()).isNull();
    assertThat(auth.tokenInjectLocation()).isNull();
    assertThat(auth.autoRefresh()).isFalse();
  }

  @Test
  @DisplayName("the response mapper section reads the converter, script and status mapping")
  void map_responseMapper() {
    columns.put("rm_id", 3L);
    columns.put("converter_type", "JSLT");
    columns.put("mapping_script", "{\"id\": .txId}");
    columns.put("mapping_status", "{\"ok\":200,\"missing\":404}".getBytes(StandardCharsets.UTF_8));
    columns.put("rm_is_active", true);

    var responseMapper = mapper.map(row).responseMapper();

    assertThat(responseMapper.converterType()).isEqualTo(ConverterType.JSLT);
    assertThat(responseMapper.mappingScript()).isEqualTo("{\"id\": .txId}");
    assertThat(responseMapper.mappingStatus()).containsEntry("ok", 200).containsEntry("missing", 404);
    assertThat(responseMapper.active()).isTrue();
  }

  @Test
  @DisplayName("a response mapper without status mapping or converter maps those to null")
  void map_responseMapperWithoutOptionalParts() {
    columns.put("rm_id", 3L);

    var responseMapper = mapper.map(row).responseMapper();

    assertThat(responseMapper.converterType()).isNull();
    assertThat(responseMapper.mappingStatus()).isNull();
    assertThat(responseMapper.active()).isFalse();
  }

  @Test
  @DisplayName("the host section is mapped, with proxy settings and defaults for missing numbers")
  void map_hostConfiguration() {
    columns.put("hc_id", 9L);
    columns.put("hc_base_url", "https://h.example.com");
    columns.put("connect_time_out_ms", 5000);
    columns.put("max_connections", 50);
    columns.put("is_use_proxy", true);
    columns.put("proxy_host", "proxy.local");
    columns.put("proxy_port", 3128);

    var host = mapper.map(row).hostConfiguration();

    assertThat(host.id()).isEqualTo(9L);
    assertThat(host.connectTimeOutMs()).isEqualTo(5000);
    assertThat(host.maxConnections()).isEqualTo(50);
    assertThat(host.pendingAcquireMaxCount()).isZero();
    assertThat(host.useProxy()).isTrue();
    assertThat(host.proxyHost()).isEqualTo("proxy.local");
    assertThat(host.proxyPort()).isEqualTo(3128);
    assertThat(host.disableSslVerification()).isFalse();
  }

  @Test
  @DisplayName("the resilience section maps floats from either Float or Double columns and defaults the rest")
  void map_resilience() {
    columns.put("r_id", 2L);
    columns.put("read_time_out_ms", 10_000);
    columns.put("cb_failure_rate_threshold", 50.0d);      // H2 style
    columns.put("cb_slow_call_rate_threshold", 80.5f);    // PostgreSQL style
    columns.put("cb_sliding_window_size", 20);
    columns.put("bh_max_concurrent_calls", 8);
    columns.put("rl_limit_for_period", 100);
    columns.put("retry_max_attempts", 3);
    columns.put("retry_on_status_codes", "500,503");
    columns.put("circuit_breaker_enabled", true);
    columns.put("retry_enabled", true);

    var resilience = mapper.map(row).resilience();

    assertThat(resilience.readTimeOutMs()).isEqualTo(10_000);
    assertThat(resilience.circuitBreaker().failureRateThreshold()).isEqualTo(50.0f);
    assertThat(resilience.circuitBreaker().slowCallRateThreshold()).isEqualTo(80.5f);
    assertThat(resilience.circuitBreaker().slidingWindowSize()).isEqualTo(20);
    assertThat(resilience.circuitBreaker().waitDurationOpenMs()).isZero();
    assertThat(resilience.bulkhead().maxConcurrentCalls()).isEqualTo(8);
    assertThat(resilience.rateLimiter().limitForPeriod()).isEqualTo(100);
    assertThat(resilience.retry().retryMaxAttempts()).isEqualTo(3);
    assertThat(resilience.retry().retryOnStatusCodes()).isEqualTo("500,503");
    assertThat(resilience.circuitBreakerEnabled()).isTrue();
    assertThat(resilience.bulkheadEnabled()).isFalse();
    assertThat(resilience.retryEnabled()).isTrue();
  }
}
