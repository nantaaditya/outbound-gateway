package com.nantaaditya.example.properties.embedded;

import io.micrometer.common.util.StringUtils;
import java.beans.Transient;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ClientConfiguration(
  String url,
  Map<String, List<String>> defaultHeaders,
  ClientTimeOutConfiguration timeOut,
  ClientProxyConfiguration proxy,
  boolean logEnabled,
  boolean sslVerificationDisabled
) {

  @Transient
  public boolean isUseProxy() {
    return Optional.ofNullable(proxy)
        .filter(p -> StringUtils.isNotBlank(p.host()) && p.port() >= 0)
        .isPresent();
  }
}
