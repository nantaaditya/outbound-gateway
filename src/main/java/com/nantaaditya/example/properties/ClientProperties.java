package com.nantaaditya.example.properties;

import com.nantaaditya.example.properties.embedded.ClientConfiguration;
import java.beans.Transient;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(value = "apps.client")
public record ClientProperties(
    Map<String, ClientConfiguration> configurations
) {

  @Transient
  public ClientConfiguration getClientConfiguration(String clientName) {
    return configurations.get(clientName);
  }

  @Transient
  public Set<String> getBeanNames(String postfix) {
    Set<String> result = new HashSet<>();
    if (configurations == null || configurations.isEmpty()) return result;

    for (Map.Entry<String, ClientConfiguration> entry : configurations.entrySet()) {
      result.add(entry.getKey() + postfix);
    }
    return result;
  }
}
