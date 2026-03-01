package com.nantaaditya.example.properties.embedded;

import io.micrometer.common.util.StringUtils;
import java.beans.Transient;

public record ClientProxyConfiguration(
    String host,
    int port,
    String username,
    String password
) {

  @Transient
  public boolean isUsingCredentials() {
    return StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password);
  }
}
