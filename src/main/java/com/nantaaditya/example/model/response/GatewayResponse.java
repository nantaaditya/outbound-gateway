package com.nantaaditya.example.model.response;

import java.util.List;
import java.util.Map;

public record GatewayResponse(
    int status,
    Map<String, List<String>> headers,
    String body,
    int convertedStatus,
    String convertedResponse,
    boolean fromCache
) {

  public static GatewayResponse passThrough(int status, Map<String, List<String>> headers, String body) {
    return new GatewayResponse(status, headers, body, status, body, false);
  }

  public static GatewayResponse mapped(int status, Map<String, List<String>> headers, String body,
      int convertedStatus, String convertedResponse) {
    return new GatewayResponse(status, headers, body, convertedStatus, convertedResponse, false);
  }

  public static GatewayResponse fromCache(int status, Map<String, List<String>> headers, String body,
      int convertedStatus, String convertedResponse) {
    return new GatewayResponse(status, headers, body, convertedStatus, convertedResponse, true);
  }

  public static GatewayResponse internalReject(int status, String reason) {
    return new GatewayResponse(status, Map.of(), reason, status, reason, false);
  }
}
