package com.nantaaditya.example.model.request;

import com.nantaaditya.example.helper.JsonHelper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

public record GatewayRequest(
    Map<String, String> queryParams,
    Object body,
    @Schema(description = "keys of outbound parameters (configured on the host) to inject into this call")
    List<String> injectKeys
) {

  public GatewayRequest(Map<String, String> queryParams, Object body) {
    this(queryParams, body, null);
  }

  public boolean hasInjectKeys() {
    return injectKeys != null && !injectKeys.isEmpty();
  }

  public boolean hasBody() {
    return body != null;
  }

  public boolean hasQueryParams() {
    return queryParams != null && !queryParams.isEmpty();
  }

  public String bodyAsString(JsonHelper jsonHelper) {
    return jsonHelper.toJsonString(body);
  }
}
