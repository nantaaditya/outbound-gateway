package com.nantaaditya.example.model.request.admin;

import jakarta.validation.constraints.NotBlank;

public record CreateOutboundSystemRequest(
    @NotBlank(message = "NotBlank")
    String code,
    @NotBlank(message = "NotBlank")
    String name,
    String description,
    @NotBlank(message = "NotBlank")
    String httpMethod,
    @NotBlank(message = "NotBlank")
    String baseUrl,
    @NotBlank(message = "NotBlank")
    String apiPath,
    boolean active,
    int idempotencyTtlHours
) {

}
