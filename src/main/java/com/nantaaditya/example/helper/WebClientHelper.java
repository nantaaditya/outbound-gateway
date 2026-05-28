package com.nantaaditya.example.helper;

import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.request.WebClientRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpMethod;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestBodySpec;
import reactor.core.publisher.Mono;

@Log4j2
public class WebClientHelper {

  private static final String PARAMETER_PATTERN = "{%s}";

  private static final Set<HttpMethod> HTTP_METHOD_WITH_PAYLOAD = Set.of(
      HttpMethod.POST,
      HttpMethod.PUT,
      HttpMethod.PATCH
  );

  private WebClientHelper() {}

  public static boolean isValidUsingPayload(HttpMethod httpMethod) {
    return HTTP_METHOD_WITH_PAYLOAD.contains(httpMethod);
  }

  public static <T> RequestBodySpec composeRequest(WebClient webClient, WebClientRequest<T> request) {
    return webClient.method(request.getHttpMethod())
        .uri(builder -> builder
            .path(composeApiPath(request.getPath(), request.getPathParameters()))
            .queryParams(toSingleMultiValueMap(request.getQueryParameters()))
            .build()
        )
        .headers(headers -> headers.putAll(toMultiValueMap(request.getHeaders())))
        .cookies(cookies -> cookies.putAll(toMultiValueMap(request.getHttpCookies())))
        .attributes(attributes -> attributes.putAll(toSingleMultiValueMap(request.getAttributes())));
  }

  public static <T> Mono<T> prepareResponse(RequestBodySpec requestBodySpec, WebClientRequest<T> request) {
    return requestBodySpec
        .retrieve()
        .bodyToMono(request.getResponseType());
  }

  public static <R> Mono<R> toResponse(WebClientRequest<R> request,
      Mono<R> result) {
    return result
        .onErrorResume(error -> {
          log.error(AppLogMessage.message("#CLIENT - got an error and return fallback {}, cause {}", error.getMessage()).error(error));
          return Mono.defer(request::getFallbackResponse);
        });
  }

  public static String composeApiPath(String apiPath, Map<String, String> parameters) {
    if (!StringUtils.hasLength(apiPath) || CollectionUtils.isEmpty(parameters)) {
      return apiPath;
    }

    String currentPath = apiPath;

    for (Map.Entry<String, String> entry : parameters.entrySet()) {
      String placeholderToReplace = String.format(PARAMETER_PATTERN, entry.getKey());
      String replacementValue = entry.getValue();
      currentPath = StringUtils.replace(currentPath, placeholderToReplace, replacementValue);
    }

    return currentPath;
  }

  public static <K, V> MultiValueMap<K, V> toMultiValueMap(Map<K, List<V>> parameters) {
    return Optional.ofNullable(parameters)
        .map(CollectionUtils::toMultiValueMap)
        .orElseGet(LinkedMultiValueMap::new);
  }

  public static <K, V> MultiValueMap<K, V> toSingleMultiValueMap(Map<K, V> parameters) {
    if (CollectionUtils.isEmpty(parameters)) {
      new LinkedMultiValueMap<>();
    }

    return parameters.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> Collections.singletonList(entry.getValue()),
            (oldValue, newValue) -> {
              oldValue.addAll(newValue);
              return oldValue;
            },
            LinkedMultiValueMap::new
        ));
  }
}
