package com.nantaaditya.example.model.response;

import com.nantaaditya.example.entity.OutboundSystem;

public record OutboundSystemResponse(
    Long id,
    String code,
    String name,
    String description,
    String httpMethod,
    String baseUrl,
    String apiPath,
    boolean active,
    int idempotencyTtlHours
) {

  public static OutboundSystemResponse from(OutboundSystem system) {
    return new OutboundSystemResponse(
        system.getId(),
        system.getCode(),
        system.getName(),
        system.getDescription(),
        system.getHttpMethod(),
        system.getBaseUrl(),
        system.getApiPath(),
        system.isActive(),
        system.getIdempotencyTtlHours()
    );
  }
}
