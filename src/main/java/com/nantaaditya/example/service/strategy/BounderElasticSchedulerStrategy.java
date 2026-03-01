package com.nantaaditya.example.service.strategy;

import com.nantaaditya.example.properties.embedded.SchedulerConfiguration;
import com.nantaaditya.example.service.SchedulerStrategy;
import java.util.Optional;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class BounderElasticSchedulerStrategy implements SchedulerStrategy {

  @Override
  public Scheduler createScheduler(SchedulerConfiguration schedulerConfiguration) {
    return Optional.ofNullable(schedulerConfiguration)
        .map(SchedulerConfiguration::getBoundedElastic)
        .map(configuration -> Schedulers.newBoundedElastic(
            configuration.corePoolSize(),
            configuration.queueSize(),
            configuration.name(),
            configuration.ttlInSeconds(),
            configuration.daemon()
        ))
        .orElseThrow(() -> new IllegalArgumentException("bounder elastic scheduler configuration is null"));
  }
}
