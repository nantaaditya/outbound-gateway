package com.nantaaditya.example.properties;

import com.nantaaditya.example.properties.embedded.ReactorEventConfiguration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(value = "apps.event")
public record ReactorEventProperties(
    Map<String, ReactorEventConfiguration> configurations
) {

}
