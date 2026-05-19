package com.nantaaditya.example.service.strategy;

import com.nantaaditya.example.properties.embedded.SchedulerConfiguration;
import com.nantaaditya.example.properties.embedded.SchedulerConfiguration.ExecutorScheduler;
import com.nantaaditya.example.service.SchedulerStrategy;
import java.util.Optional;
import java.util.concurrent.Executors;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class ExecutorSchedulerStrategy implements SchedulerStrategy {

  @Override
  public Scheduler createScheduler(SchedulerConfiguration schedulerConfiguration) {
    return Optional.ofNullable(schedulerConfiguration)
        .map(SchedulerConfiguration::getExecutor)
        .map(this::buildScheduler)
        .orElseThrow(() -> new IllegalArgumentException("executor scheduler configuration is null"));
  }

  private Scheduler buildScheduler(ExecutorScheduler configuration) {
    return switch (configuration.executorType()) {
      case SINGLE_THREAD_POOL -> Schedulers.fromExecutorService(
          Executors.newSingleThreadExecutor()
      );
      case FIXED_THREAD_POOL ->  Schedulers.fromExecutorService(
          Executors.newFixedThreadPool(configuration.corePoolSize())
      );
      case CACHED_THREAD_POOL ->   Schedulers.fromExecutorService(
          Executors.newCachedThreadPool()
      );
      case WORK_STEALING_POOL ->   Schedulers.fromExecutorService(
          Executors.newWorkStealingPool(configuration.corePoolSize())
      );
    };
  }
}
