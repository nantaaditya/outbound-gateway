package com.nantaaditya.example.model.constant;

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
  REMOVE_OBSOLETE_DEAD_LETTER_PROCESS(HttpMethod.DELETE, "/internal-api/dead_letter_process"),;

  private HttpMethod method;
  private String path;

  private static final AntPathMatcher matcher = new AntPathMatcher();

  FeatureConstant(HttpMethod method, String path) {
    this.method = method;
    this.path = path;
  }

  public static FeatureConstant get(String method, String path) {
    Predicate<FeatureConstant> isMatch = (FeatureConstant item)
      -> item.getMethod() == HttpMethod.valueOf(method) && matcher.match(item.getPath(), path);

    return Stream.of(values())
        .filter(isMatch)
        .findFirst()
        .orElse(null);
  }
}
