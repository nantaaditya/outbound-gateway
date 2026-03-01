package com.nantaaditya.example.service;

import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

public interface WebClientService {

  <T> Builder<T> builder();

  interface Builder<T> {
    WebClientService.Request<T> post(String path);

    WebClientService.Request<T> put(String path);

    WebClientService.Request<T> patch(String path);

    WebClientService.Request<T> get(String path);

    WebClientService.Request<T> delete(String path);

    WebClientService.Request<T> option(String path);

    WebClientService.Request<T> head(String path);

    Mono<T> execute();
  }

  interface Request<T> {
    WebClientService.Request<T> pathParameters(Map<String, String> pathParameters);

    WebClientService.Request<T> queryParameters(Map<String, String> queryParameters);

    WebClientService.Request<T> headers(Map<String, List<String>> headers);

    WebClientService.Request<T> cookies(Map<String, List<String>> cookies);

    WebClientService.Request<T> payload(Object payload);

    WebClientService.Request<T> attributes(Map<String, Object> attributes);

    WebClientService.Request<T> fallback(Mono<T> fallback);

    Mono<T> execute();
  }
}
