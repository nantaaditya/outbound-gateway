package com.nantaaditya.example.properties;

import com.nantaaditya.example.properties.embedded.CacheConfiguration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("apps.cache")
public record CacheProperties(
    Map<String, CacheConfiguration> configurations
) {

  public CacheConfiguration get(String key) {
    return configurations.get(key);
  }
}
