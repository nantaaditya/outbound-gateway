package com.nantaaditya.example.model.request;

import com.nantaaditya.example.helper.JsonHelper;
import java.util.Map;

public record GatewayRequest(
    Map<String, String> queryParams,
    Object body
) {

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
