package com.nantaaditya.example.model.response;

import java.util.List;
import java.util.Map;

public record GatewayResponse(
    int status,
    Map<String, List<String>> headers,
    String body,
    int convertedStatus,
    String convertedResponse,
    boolean fromCache,
    boolean mappingFallback
) {

  public static GatewayResponse passThrough(int status, Map<String, List<String>> headers, String body) {
    return new GatewayResponse(status, headers, body, status, body, false, false);
  }

  public static GatewayResponse mapped(int status, Map<String, List<String>> headers, String body,
      int convertedStatus, String convertedResponse) {
    return new GatewayResponse(status, headers, body, convertedStatus, convertedResponse, false, false);
  }

  /** JSLT transform failed; {@code convertedResponse} falls back to the original body. */
  public static GatewayResponse mappedWithFallback(int status, Map<String, List<String>> headers,
      String body, int convertedStatus) {
    return new GatewayResponse(status, headers, body, convertedStatus, body, false, true);
  }

  public static GatewayResponse fromCache(int status, Map<String, List<String>> headers, String body,
      int convertedStatus, String convertedResponse) {
    return new GatewayResponse(status, headers, body, convertedStatus, convertedResponse, true, false);
  }

  public static GatewayResponse internalReject(int status, String reason) {
    return new GatewayResponse(status, Map.of(), reason, status, reason, false, false);
  }
}
