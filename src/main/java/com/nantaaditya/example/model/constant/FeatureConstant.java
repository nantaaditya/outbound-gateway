package com.nantaaditya.example.model.constant;

import java.util.List;
import java.util.Optional;
import lombok.Getter;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

@Getter
public enum FeatureConstant {

  REMOVE_OBSOLETE_EVENT_LOG(HttpMethod.DELETE, "/internal-api/event_log"),
  REMOVE_OBSOLETE_DEAD_LETTER_PROCESS(HttpMethod.DELETE, "/internal-api/dead_letter_process"),
  OUTBOUND_HISTORIES(HttpMethod.GET, "/api/gateway/histories"),
  CHECK_IDEMPOTENT_REQUEST(HttpMethod.GET, "/api/gateway/histories/*"),
  OUTBOUND_CALL(HttpMethod.POST, "/api/gateway/execute/*"),
  CREATE_OUTBOUND_HOST(HttpMethod.POST, "/api/outbound/host"),
  LIST_OUTBOUND_HOST(HttpMethod.GET, "/api/outbound/host"),
  GET_OUTBOUND_HOST(HttpMethod.GET, "/api/outbound/host/*"),
  UPDATE_OUTBOUND_HOST(HttpMethod.PUT, "/api/outbound/host/*"),
  RELOAD_OUTBOUND_HOST(HttpMethod.GET, "/api/outbound/host/*/reload"),
  CREATE_OUTBOUND(HttpMethod.POST, "/api/outbound"),
  LIST_OUTBOUNDS(HttpMethod.GET, "/api/outbound"),
  GET_OUTBOUND(HttpMethod.GET, "/api/outbound/*"), //NOSONAR
  UPDATE_OUTBOUND(HttpMethod.PUT, "/api/outbound/*"), //NOSONAR
  DELETE_OUTBOUND(HttpMethod.DELETE, "/api/outbound/*"), //NOSONAR
  UPSERT_RESILIENCE(HttpMethod.POST, "/api/outbound/*/resilience"),
  UPSERT_HEADERS(HttpMethod.POST, "/api/outbound/*/headers"),
  UPSERT_AUTH(HttpMethod.POST, "/api/outbound/*/authentication"),
  UPSERT_RESPONSE_MAPPER(HttpMethod.POST, "/api/outbound/*/response-mapper"),
  OUTBOUND_RELOAD(HttpMethod.POST, "/api/outbound/*/reload"),
  OUTBOUND_TEST(HttpMethod.POST, "/api/outbound/*/test"),
  GET_METRICS(HttpMethod.GET, "/api/outbound/*/metrics"),;

  private final HttpMethod method;
  private final String path;

  private static final AntPathMatcher matcher = new AntPathMatcher();
  private static final List<FeatureConstant> VALUES = List.of(values());

  FeatureConstant(HttpMethod method, String path) {
    this.method = method;
    this.path = path;
  }

  public static Optional<FeatureConstant> find(String method, String path) {
    if (method == null || method.isBlank() || path == null || path.isBlank()) {
      return Optional.empty();
    }
    HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase());
    return VALUES.stream()
        .filter(item -> item.getMethod().equals(httpMethod) && matcher.match(item.getPath(), path))
        .findFirst();
  }
}
