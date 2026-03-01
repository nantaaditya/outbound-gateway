package com.nantaaditya.example.base;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;

public record RestApiRequest<S, T>(
    HttpMethod httpMethod,
    String path,
    Map<String, List<String>> queryParameters,
    S payload,
    RestApiAssertion<T> assertion,
    DelayTestCase delay
) {
  public boolean isEligibleUsePayload() {
    return Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH).contains(httpMethod);
  }
}