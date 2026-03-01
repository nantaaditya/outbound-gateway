package com.nantaaditya.example.service.strategy;

import com.nantaaditya.example.properties.embedded.SchedulerConfiguration;
import com.nantaaditya.example.service.SchedulerStrategy;
import java.util.Optional;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class SingleSchedulerStrategy implements SchedulerStrategy {

  @Override
  public Scheduler createScheduler(SchedulerConfiguration schedulerConfiguration) {
    return Optional.ofNullable(schedulerConfiguration)
        .map(SchedulerConfiguration::getSingle)
        .map(configuration -> Schedulers.newSingle(configuration.name(), configuration.daemon()))
        .orElseThrow(() -> new IllegalArgumentException("scheduler configuration is null"));
  }
}
