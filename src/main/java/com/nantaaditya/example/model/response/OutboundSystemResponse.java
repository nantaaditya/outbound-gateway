package com.nantaaditya.example.model.response;

import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;

public record OutboundSystemResponse(
    Long id,
    String code,
    String name,
    String description,
    String httpMethod,
    Long hostConfigurationId,
    String baseUrl,
    String apiPath,
    boolean active,
    int idempotencyTtlHours
) {

  public static OutboundSystemResponse from(OutboundSystem system, OutboundHostConfiguration hostConfig) {
    return new OutboundSystemResponse(
        system.getId(),
        system.getCode(),
        system.getName(),
        system.getDescription(),
        system.getHttpMethod(),
        system.getHostConfigurationId(),
        hostConfig.getBaseUrl(),
        system.getApiPath(),
        system.isActive(),
        system.getIdempotencyTtlHours()
    );
  }
}
