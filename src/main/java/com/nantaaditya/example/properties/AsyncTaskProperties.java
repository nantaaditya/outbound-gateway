package com.nantaaditya.example.properties;

import com.nantaaditya.example.properties.embedded.AsyncConfiguration;
import java.beans.Transient;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("apps.async")
public record AsyncTaskProperties(
    Map<String, AsyncConfiguration> configurations
) {

  @Transient
  public AsyncConfiguration getConfiguration(String name) {
    return configurations.get(name);
  }

  public Set<String> getBeanNames(String postfix) {
    Set<String> result = new HashSet<>();
    if (configurations == null || configurations.isEmpty()) return result;

    for (Map.Entry<String, AsyncConfiguration> entry : configurations.entrySet()) {
      result.add(entry.getKey() + postfix);
    }
    return result;
  }
}
