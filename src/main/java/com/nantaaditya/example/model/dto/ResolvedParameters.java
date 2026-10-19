package com.nantaaditya.example.model.dto;

import com.nantaaditya.example.model.constant.ParameterInjectLocation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ResolvedParameters(List<ResolvedParameter> items) {

  private static final ResolvedParameters EMPTY = new ResolvedParameters(List.of());

  public static ResolvedParameters empty() {
    return EMPTY;
  }

  public boolean isEmpty() {
    return items.isEmpty();
  }

  public Map<String, String> headers() {
    return valuesAt(ParameterInjectLocation.HEADER);
  }

  public Map<String, String> queryParams() {
    return valuesAt(ParameterInjectLocation.QUERY_PARAM);
  }

  public List<ResolvedParameter> bodyItems() {
    return items.stream()
        .filter(p -> p.location() == ParameterInjectLocation.BODY)
        .toList();
  }

  private Map<String, String> valuesAt(ParameterInjectLocation location) {
    Map<String, String> result = new LinkedHashMap<>();
    items.stream()
        .filter(p -> p.location() == location)
        .forEach(p -> result.put(p.targetName(), p.value()));
    return result;
  }
}
