package com.nantaaditya.example.helper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.response.OutboundSystemDetailResponse;
import com.nantaaditya.example.model.response.OutboundSystemDetailResponse.AuthDetail;
import com.nantaaditya.example.model.response.OutboundSystemDetailResponse.HostConfigDetail;
import com.nantaaditya.example.model.response.OutboundSystemDetailResponse.ResilienceDetail;
import com.nantaaditya.example.model.response.OutboundSystemDetailResponse.ResponseMapperDetail;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Maps a flat {@code Row} from {@code v_outbound_system_detail} to a nested
 * {@link OutboundSystemDetailResponse}.
 *
 * <p>Each optional config section (resilience, auth, response-mapper, host-config) is present
 * only when its LEFT JOIN produced a non-null id column (e.g. {@code r_id}).
 *
 * <p>Headers are deserialized from the raw column value: {@code byte[]} in H2 integration tests
 * (bytea column), {@code io.r2dbc.postgresql.codec.Json} in production PostgreSQL (jsonb column).
 * Sensitive header names are redacted to {@code "***"} before the response is built.
 */
@Component
@RequiredArgsConstructor
public class OutboundSystemDetailRowMapper {

  // Same set used in OutboundServiceImpl; kept here so redaction lives next to deserialization.
  private static final Set<String> SENSITIVE_HEADER_NAMES = Set.of(
      "authorization", "proxy-authorization", "x-api-key", "x-auth-token",
      "x-secret", "x-client-secret", "x-access-token"
  );

  private final JsonHelper jsonHelper;

  public OutboundSystemDetailResponse map(Readable row) {
    return new OutboundSystemDetailResponse(
        row.get("id", Long.class),
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("description", String.class),
        row.get("http_method", String.class),
        row.get("base_url", String.class),
        row.get("api_path", String.class),
        Boolean.TRUE.equals(row.get("is_active", Boolean.class)),
        getInt(row, "idempotency_ttl_hours"),
        mapResilience(row),
        mapHeaders(row),
        mapAuth(row),
        mapResponseMapper(row),
        mapHostConfig(row)
    );
  }

  private ResilienceDetail mapResilience(Readable row) {
    if (row.get("r_id", Long.class) == null) return null;
    return new ResilienceDetail(
        getInt(row, "read_time_out_ms"),
        getFloat(row, "cb_failure_rate_threshold"),
        getFloat(row, "cb_slow_call_rate_threshold"),
        getInt(row, "cb_slow_call_duration_ms"),
        getInt(row, "cb_permitted_in_half_open"),
        getInt(row, "cb_sliding_window_size"),
        getInt(row, "cb_wait_duration_open_ms"),
        getInt(row, "bh_max_concurrent_calls"),
        getInt(row, "bh_max_wait_duration_ms"),
        getInt(row, "rl_limit_for_period"),
        getInt(row, "rl_limit_refresh_period_ms"),
        getInt(row, "rl_timeout_duration_ms"),
        getInt(row, "retry_max_attempts"),
        getInt(row, "retry_wait_duration_ms"),
        row.get("retry_on_status_codes", String.class),
        getBool(row, "circuit_breaker_enabled"),
        getBool(row, "bulkhead_enabled"),
        getBool(row, "rate_limiter_enabled"),
        getBool(row, "retry_enabled")
    );
  }

  private List<Map<String, Object>> mapHeaders(Readable row) {
    Object raw = row.get("h_headers", Object.class);
    if (raw == null) return Collections.emptyList();
    Map<String, Object> headers;
    if (raw instanceof byte[] bytes) {
      headers = jsonHelper.convert(bytes, new TypeReference<>() {});
    } else if (raw instanceof Json json) {
      headers = jsonHelper.fromJson(json, new TypeReference<>() {});
    } else {
      return Collections.emptyList();
    }
    if (headers == null || headers.isEmpty()) return Collections.emptyList();
    return List.of(scrubSensitiveHeaders(headers));
  }

  private AuthDetail mapAuth(Readable row) {
    if (row.get("a_id", Long.class) == null) return null;
    String authTypeStr = row.get("auth_type", String.class);
    String locationStr = row.get("token_inject_location", String.class);
    return new AuthDetail(
        authTypeStr != null ? AuthType.valueOf(authTypeStr) : null,
        getBool(row, "is_auto_refresh"),
        row.get("token_method", String.class),
        row.get("token_url", String.class),
        row.get("token_body", String.class),
        row.get("token_prefix", String.class),
        locationStr != null ? TokenInjectLocation.valueOf(locationStr) : null,
        row.get("token_key_name", String.class),
        row.get("token_response_path", String.class),
        row.get("token_response_expiry_path", String.class)
    );
  }

  private ResponseMapperDetail mapResponseMapper(Readable row) {
    if (row.get("rm_id", Long.class) == null) return null;
    String converterTypeStr = row.get("converter_type", String.class);
    byte[] statusBytes = row.get("mapping_status", byte[].class);
    Map<String, Integer> mappingStatus = statusBytes != null
        ? jsonHelper.convert(statusBytes, new TypeReference<>() {})
        : null;
    return new ResponseMapperDetail(
        converterTypeStr != null ? ConverterType.valueOf(converterTypeStr) : null,
        row.get("mapping_script", String.class),
        mappingStatus,
        getBool(row, "rm_is_active")
    );
  }

  private HostConfigDetail mapHostConfig(Readable row) {
    if (row.get("hc_id", Long.class) == null) return null;
    return new HostConfigDetail(
        row.get("hc_id", Long.class),
        row.get("hc_base_url", String.class),
        getInt(row, "connect_time_out_ms"),
        getInt(row, "max_connections"),
        getInt(row, "pending_acquire_max_count"),
        getInt(row, "pending_acquire_timeout_ms"),
        getBool(row, "is_disable_ssl_verification"),
        getBool(row, "is_use_proxy"),
        row.get("proxy_host", String.class),
        getInt(row, "proxy_port")
    );
  }

  private Map<String, Object> scrubSensitiveHeaders(Map<String, Object> headers) {
    Map<String, Object> scrubbed = new HashMap<>();
    headers.forEach((k, v) -> scrubbed.put(k,
        SENSITIVE_HEADER_NAMES.contains(k.toLowerCase()) ? "***" : v));
    return scrubbed;
  }

  private int getInt(Readable row, String col) {
    return Optional.ofNullable(row.get(col, Integer.class)).orElse(0);
  }

  private float getFloat(Readable row, String col) {
    // H2 returns FLOAT as Double, PostgreSQL as Float — both extend Number.
    // instanceof pattern avoids ClassCastException on unexpected column types.
    Object raw = row.get(col, Object.class);
    return raw instanceof Number n ? n.floatValue() : 0f;
  }

  private boolean getBool(Readable row, String col) {
    return Boolean.TRUE.equals(row.get(col, Boolean.class));
  }
}
