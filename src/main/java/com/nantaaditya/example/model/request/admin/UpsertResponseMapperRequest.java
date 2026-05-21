package com.nantaaditya.example.model.request.admin;

import com.nantaaditya.example.model.constant.ConverterType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record UpsertResponseMapperRequest(
    @NotNull(message = "NotNull")
    ConverterType converterType,
    String mappingScript,
    Map<String, Integer> mappingStatus,
    boolean active
) {
  public UpsertResponseMapperRequest {
    mappingStatus = mappingStatus != null ? Map.copyOf(mappingStatus) : null;
  }

  @AssertTrue(message = "NotValid")
  public boolean isMappingScriptValid() {
    if (converterType == null || converterType == ConverterType.NONE) return true;
    return mappingScript != null && !mappingScript.isBlank();
  }
}
