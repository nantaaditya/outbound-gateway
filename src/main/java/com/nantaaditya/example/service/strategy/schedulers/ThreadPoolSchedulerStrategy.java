package com.nantaaditya.example.service.strategy;

import com.nantaaditya.example.properties.embedded.SchedulerConfiguration;
import com.nantaaditya.example.properties.embedded.SchedulerConfiguration.ThreadPoolScheduler;
import com.nantaaditya.example.service.SchedulerStrategy;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class ThreadPoolSchedulerStrategy implements SchedulerStrategy {

  @Override
  public Scheduler createScheduler(SchedulerConfiguration schedulerConfiguration) {
    return Optional.ofNullable(schedulerConfiguration)
        .map(SchedulerConfiguration::getThreadPool)
        .map(configuration -> buildThreadPool(configuration))
        .orElseThrow(() -> new IllegalArgumentException("thread pool scheduler configuration is null"));
  }

  private Scheduler buildThreadPool(ThreadPoolScheduler properties) {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(
        properties.corePoolSize(),
        properties.maxPoolSize(),
        properties.ttlInSeconds(),
        TimeUnit.SECONDS,
        buildQueue(properties)
    );

    executor.allowCoreThreadTimeOut(properties.allowCoreThreadTimeOut());
    return Schedulers.fromExecutor(executor);
  }

  private BlockingQueue<Runnable> buildQueue(ThreadPoolScheduler properties) {
    return switch (properties.queueType()) {
      case ARRAY -> new ArrayBlockingQueue<>(properties.queueSize());
      case LINKED -> new LinkedBlockingQueue<>(properties.queueSize());
    };
  }
}
