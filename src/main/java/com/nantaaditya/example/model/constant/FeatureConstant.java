package com.nantaaditya.example.model.constant;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import lombok.Getter;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

@Getter
public enum FeatureConstant {

  GET_EXAMPLE(HttpMethod.GET, "/api/example"),
  POST_EXAMPLE(HttpMethod.POST, "/api/example"),
  REMOVE_OBSOLETE_EVENT_LOG(HttpMethod.DELETE, "/internal-api/event_log"),
  REMOVE_OBSOLETE_DEAD_LETTER_PROCESS(HttpMethod.DELETE, "/internal-api/dead_letter_process"),
  OUTBOUND_HISTORIES(HttpMethod.GET, "/gateway/histories"),
  CHECK_IDEMPOTENT_REQUEST(HttpMethod.GET, "/api/gateway/histories/*"),
  OUTBOUND_CALL(HttpMethod.POST, "/api/gateway/execute/*"),
  CREATE_OUTBOUND(HttpMethod.POST, "/api/outbound"),
  LIST_OUTBOUNDS(HttpMethod.GET, "/api/outbound"),
  GET_OUTBOUND(HttpMethod.GET, "/api/outbound/*"),
  UPDATE_OUTBOUND(HttpMethod.PUT, "/api/outbound/*"),
  DELETE_OUTBOUND(HttpMethod.DELETE, "/api/outbound/*"),
  UPSERT_RESILIENCE(HttpMethod.POST, "/api/outbound/*/resilience"),
  UPSERT_HEADERS(HttpMethod.POST, "/api/outbound/*/headers"),
  UPSERT_AUTH(HttpMethod.POST, "/api/outbound/*/auth"),
  UPSERT_RESPONSE_MAPPER(HttpMethod.POST, "/api/outbound/*/response-mapper"),
  OUTBOUND_RELOAD(HttpMethod.POST, "/api/outbound/*/reload"),
  OUTBOUND_TEST(HttpMethod.POST, "/api/outbound/*/test"),
  GET_METRICS(HttpMethod.GET, "/api/outbound/*/metrics"),;

  private final HttpMethod method;
  private final String path;

  private static final AntPathMatcher matcher = new AntPathMatcher();

  FeatureConstant(HttpMethod method, String path) {
    this.method = method;
    this.path = path;
  }

  public static Optional<FeatureConstant> find(String method, String path) {
    Predicate<FeatureConstant> isMatch = (FeatureConstant item)
      -> item.getMethod() == HttpMethod.valueOf(method) && matcher.match(item.getPath(), path);

    return Stream.of(values())
        .filter(isMatch)
        .findFirst();
  }
}
