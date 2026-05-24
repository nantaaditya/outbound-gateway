package com.nantaaditya.example.model.request.admin;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateHostConfigRequest(
    @NotBlank(message = "NotBlank")
    String baseUrl,
    @Min(value = 1, message = "Min")
    int connectTimeOutMs,
    @Min(value = 1, message = "Min")
    int maxConnections,
    @Min(value = 1, message = "Min")
    int pendingAcquireMaxCount,
    @Min(value = 0, message = "Min")
    int pendingAcquireTimeoutMs,
    boolean disableSslVerification,
    boolean useProxy,
    String proxyHost,
    int proxyPort,
    String proxyUsername,
    String proxyPassword
) {
  @JsonIgnore
  @AssertTrue(message = "proxyHost and proxyPort are required when useProxy is true")
  public boolean isProxyConfigValid() {
    if (!useProxy) return true;
    return proxyHost != null && !proxyHost.isBlank() && proxyPort > 0;
  }
}
