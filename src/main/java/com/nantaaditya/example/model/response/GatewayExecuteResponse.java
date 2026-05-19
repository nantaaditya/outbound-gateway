package com.nantaaditya.example.model.response;

import com.nantaaditya.example.helper.JsonHelper;
import java.util.List;
import java.util.Map;

public record GatewayExecuteResponse(
    int status,
    Map<String, List<String>> headers,
    Object body,
    int convertedStatus,
    Object convertedResponse,
    boolean fromCache
) {

  public static GatewayExecuteResponse from(GatewayResponse response, JsonHelper jsonHelper) {
    return new GatewayExecuteResponse(
        response.status(),
        response.headers(),
        jsonHelper.parseJsonOrRaw(response.body()),
        response.convertedStatus(),
        jsonHelper.parseJsonOrRaw(response.convertedResponse()),
        response.fromCache()
    );
  }
}
