package com.nantaaditya.example.model.request.admin;

import com.nantaaditya.example.model.constant.ConverterType;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;

public record UpsertResponseMapperRequest(
    @NotNull(message = "NotNull")
    ConverterType converterType,
    String mappingScript,
    Map<String, Integer> mappingStatus,
    boolean active
) {
  public Map<String, Integer> getMappingStatus() {
    if (mappingStatus == null) return null;
    return new HashMap<>(mappingStatus);
  }
}
