package com.nantaaditya.example.properties;

import com.nantaaditya.example.properties.embedded.RetryConfiguration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(value = "apps.retry")
public record RetryProperties(
    Map<String, RetryConfiguration> configurations
) {
}
