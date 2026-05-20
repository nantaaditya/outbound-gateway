package com.nantaaditya.example.model.request.admin;

import com.nantaaditya.example.model.constant.ConverterType;
import java.util.HashMap;
import java.util.Map;

public record UpsertResponseMapperRequest(
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
