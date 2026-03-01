package com.nantaaditya.example.configuration;

import com.nantaaditya.example.factory.impl.SchedulerHelperFactoryBean;
import com.nantaaditya.example.properties.SchedulerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class SchedulerConfiguration {

  private final SchedulerProperties schedulerProperties;

  @Bean
  public SchedulerHelperFactoryBean schedulerHelper() {
    return new SchedulerHelperFactoryBean(schedulerProperties);
  }
}
