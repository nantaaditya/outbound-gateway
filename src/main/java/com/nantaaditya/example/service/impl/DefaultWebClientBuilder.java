package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.helper.WebClientHelper;
import com.nantaaditya.example.model.request.WebClientPayloadRequest;
import com.nantaaditya.example.model.request.WebClientRequest;
import com.nantaaditya.example.service.WebClientService;
import com.nantaaditya.example.service.WebClientService.Builder;
import com.nantaaditya.example.service.WebClientService.Request;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.util.ObjectUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestBodySpec;
import reactor.core.publisher.Mono;

public class DefaultWebClientBuilder<T> implements Builder<T> {

  private WebClientRequest<T> request;

  private WebClient webClient;

  private DefaultWebClientBuilder() {}

  public DefaultWebClientBuilder(WebClient webClient) {
    this.webClient = webClient;
  }

  @Override
  public Request<T> post(String path) {
    return buildRequest(HttpMethod.POST, path);
  }

  @Override
  public Request<T> put(String path) {
    return buildRequest(HttpMethod.PUT, path);
  }

  @Override
  public Request<T> patch(String path) {
    return buildRequest(HttpMethod.PATCH, path);
  }

  @Override
  public Request<T> get(String path) {
    return buildRequest(HttpMethod.GET, path);
  }

  @Override
  public Request<T> delete(String path) {
    return buildRequest(HttpMethod.DELETE, path);
  }

  @Override
  public Request<T> option(String path) {
    return buildRequest(HttpMethod.OPTIONS, path);
  }

  @Override
  public Request<T> head(String path) {
    return buildRequest(HttpMethod.HEAD, path);
  }

  @Override
  public Mono<T> execute() {
    if (ObjectUtils.isEmpty(webClient)) {
      return Mono.error(new IllegalArgumentException("#CLIENT - client not configured"));
    }

    if (request == null || !request.isValid()) {
      return Mono.error(new IllegalArgumentException("#CLIENT - request can't be null"));
    }

    RequestBodySpec requestBodySpec = WebClientHelper.composeRequest(webClient, request);

    if (WebClientHelper.isValidUsingPayload(request.getHttpMethod())) {
      requestBodySpec.bodyValue(((WebClientPayloadRequest) request).getRequestBody());
    }

    Mono<T> response = WebClientHelper.prepareResponse(requestBodySpec, request);
    return WebClientHelper.toResponse(request, response);
  }

  private WebClientService.Request<T> buildRequest(HttpMethod method, String path) {
    request = WebClientHelper.isValidUsingPayload(method) ?
        new WebClientPayloadRequest() : new WebClientRequest();
    request.setHttpMethod(method);
    request.setPath(path);
    request.setResponseType(new ParameterizedTypeReference<T>() {});
    return new DefaultWebClientRequest<>(this, request);
  }
}
