package com.nantaaditya.example.configuration;

import com.nantaaditya.example.helper.ReactorEventBusHelper;
import com.nantaaditya.example.helper.RetryHelper;
import com.nantaaditya.example.properties.ReactorEventProperties;
import com.nantaaditya.example.properties.RetryProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Sinks;

@Configuration
@RequiredArgsConstructor
public class ReactorEventBusConfiguration {

  private final ReactorEventBusHelper reactorEventBusHelper;
  private final ReactorEventProperties reactorEventProperties;
  private final RetryProperties retryProperties;

  private static final int DEFAULT_BUFFER_SIZE = 50;

  @PostConstruct
  public void createSinks() {
    if (null != retryProperties.configurations()) {
      retryProperties.configurations()
          .forEach((key, value) -> {
            reactorEventBusHelper.createSinks(
                key + RetryHelper.BEFORE_SUFFIX_EVENT,
                Sinks.many().multicast().onBackpressureBuffer(DEFAULT_BUFFER_SIZE)
            );

            reactorEventBusHelper.createSinks(
                key + RetryHelper.AFTER_SUFFIX_EVENT,
                Sinks.many().multicast().onBackpressureBuffer(DEFAULT_BUFFER_SIZE)
            );
          });
    }

    if (null != reactorEventProperties.configurations()) {
      reactorEventProperties.configurations()
          .forEach((key, value) -> {
            reactorEventBusHelper.createSinks(
                key,
                Sinks.many().multicast().onBackpressureBuffer(value.bufferSize()));
          });
    }
  }
}
