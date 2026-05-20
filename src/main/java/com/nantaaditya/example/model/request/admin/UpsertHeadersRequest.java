package com.nantaaditya.example.model.request.admin;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

public record UpsertHeadersRequest(
    @NotEmpty(message = "NotEmpty")
    Map<String, List<String>> headers
) {}
