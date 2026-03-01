package com.nantaaditya.example.model.request;

import io.micrometer.common.util.StringUtils;
import java.beans.Transient;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Mono;

@Data
@SuperBuilder
@NoArgsConstructor
public class WebClientRequest<T> {
  private HttpMethod httpMethod;
  private String path;
  private Map<String, String> pathParameters;
  private Map<String, String> queryParameters;
  private Map<String, List<String>> httpCookies;
  private Map<String, List<String>> headers;
  private Map<String, Object> attributes;
  private ParameterizedTypeReference<T> responseType;
  private Mono<T> fallbackResponse = Mono.empty();

  @Transient
  public boolean isValid() {
    return httpMethod != null && StringUtils.isNotBlank(path) && responseType != null;
  }
}
