package com.nantaaditya.example;

import com.nantaaditya.example.properties.AsyncTaskProperties;
import com.nantaaditya.example.properties.ClientProperties;
import com.nantaaditya.example.properties.LogProperties;
import com.nantaaditya.example.properties.ReactorEventProperties;
import com.nantaaditya.example.properties.RetryProperties;
import com.nantaaditya.example.properties.SchedulerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import reactor.core.publisher.Hooks;

@SpringBootApplication
@EnableR2dbcAuditing
@EnableConfigurationProperties(value = {
    AsyncTaskProperties.class,
    ClientProperties.class,
    LogProperties.class,
    ReactorEventProperties.class,
    RetryProperties.class,
    SchedulerProperties.class
})
public class SingleModuleReactiveApplication {

  public static void main(String[] args) {
    Hooks.enableAutomaticContextPropagation();
    SpringApplication.run(SingleModuleReactiveApplication.class, args);
  }

}
