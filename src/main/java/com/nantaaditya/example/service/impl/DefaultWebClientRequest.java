package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.model.request.WebClientPayloadRequest;
import com.nantaaditya.example.model.request.WebClientRequest;
import com.nantaaditya.example.service.WebClientService;
import com.nantaaditya.example.service.WebClientService.Request;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

public class DefaultWebClientRequest<T> implements Request<T> {

  private WebClientRequest<T> request;
  private WebClientService.Builder<T> builder;

  private DefaultWebClientRequest() {}

  public DefaultWebClientRequest(
      WebClientService.Builder<T> builder,
      WebClientRequest<T> request) {
    this.builder = builder;
    this.request = request;
  }

  @Override
  public Request<T> pathParameters(Map<String, String> pathParameters) {
    if (pathParameters != null) {
      request.setPathParameters(pathParameters);
    }
    return this;
  }

  @Override
  public Request<T> queryParameters(Map<String, String> queryParameters) {
    if (queryParameters != null) {
      request.setQueryParameters(queryParameters);
    }
    return this;
  }

  @Override
  public Request<T> headers(Map<String, List<String>> headers) {
    if (headers != null) {
      request.setHeaders(headers);
    }
    return this;
  }

  @Override
  public Request<T> cookies(Map<String, List<String>> cookies) {
    if (cookies != null) {
      request.setHttpCookies(cookies);
    }
    return this;
  }

  @Override
  public Request<T> payload(Object payload) {
    if (payload != null && request instanceof WebClientPayloadRequest<T> payloadRequest) {
      payloadRequest.setRequestBody(payload);
      return this;
    } else {
      throw new IllegalArgumentException("http method not supported to use payload");
    }
  }

  @Override
  public Request<T> attributes(Map<String, Object> attributes) {
    if (attributes != null) {
      request.setAttributes(attributes);
    }
    return this;
  }

  @Override
  public Request<T> fallback(Mono<T> fallback) {
    if (fallback != null) {
      request.setFallbackResponse(fallback);
    }
    return this;
  }

  @Override
  public Mono<T> execute() {
    return builder.execute();
  }
}
