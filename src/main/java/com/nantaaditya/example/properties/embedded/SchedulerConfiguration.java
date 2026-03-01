package com.nantaaditya.example.properties.embedded;

import com.nantaaditya.example.model.constant.ExecutorType;
import com.nantaaditya.example.model.constant.QueueType;
import com.nantaaditya.example.model.constant.SchedulerType;
import lombok.Data;

@Data
public class SchedulerConfiguration {
  private SchedulerType schedulerType;
  private SingleScheduler single;
  private ParallelScheduler parallel;
  private BoundedElasticScheduler boundedElastic;
  private ExecutorScheduler executor;
  private ThreadPoolScheduler threadPool;

  public record SingleScheduler(
      String name,
      boolean daemon
  ) {}

  public record ParallelScheduler(
      String name,
      boolean daemon,
      int corePoolSize
  ) {}

  public record BoundedElasticScheduler(
      String name,
      boolean daemon,
      int corePoolSize,
      int queueSize,
      int ttlInSeconds
  ) {}

  public record ExecutorScheduler(
      ExecutorType executorType,
      int corePoolSize
  ) {}

  public record ThreadPoolScheduler(
      int corePoolSize,
      int maxPoolSize,
      int queueSize,
      boolean allowCoreThreadTimeOut,
      int ttlInSeconds,
      QueueType queueType
  ) {}
}
