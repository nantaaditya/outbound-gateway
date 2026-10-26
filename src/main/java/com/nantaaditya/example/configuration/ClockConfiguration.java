package com.nantaaditya.example.configuration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one source of "now" for code that signs or expires things, so tests can pin the time. */
@Configuration
public class ClockConfiguration {

  @Bean
  public Clock clock() {
    return Clock.systemDefaultZone();
  }
}
