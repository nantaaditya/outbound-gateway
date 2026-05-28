package com.nantaaditya.example.service.strategy.schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nantaaditya.example.model.constant.QueueType;
import com.nantaaditya.example.properties.embedded.SchedulerConfiguration;
import com.nantaaditya.example.properties.embedded.SchedulerConfiguration.ThreadPoolScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;

class ThreadPoolSchedulerStrategyTest {

  private ThreadPoolSchedulerStrategy strategy;

  @BeforeEach
  void setUp() {
    strategy = new ThreadPoolSchedulerStrategy();
  }

  @Test
  @DisplayName("createScheduler with ARRAY queue returns a non-null Scheduler")
  void createScheduler_arrayQueue_returnsScheduler() {
    SchedulerConfiguration config = configWith(QueueType.ARRAY);

    Scheduler scheduler = strategy.createScheduler(config);

    assertThat(scheduler).isNotNull();
  }

  @Test
  @DisplayName("createScheduler with LINKED queue returns a non-null Scheduler")
  void createScheduler_linkedQueue_returnsScheduler() {
    SchedulerConfiguration config = configWith(QueueType.LINKED);

    Scheduler scheduler = strategy.createScheduler(config);

    assertThat(scheduler).isNotNull();
  }

  @Test
  @DisplayName("createScheduler with null config throws IllegalArgumentException")
  void createScheduler_nullConfig_throwsIllegalArgument() {
    assertThatThrownBy(() -> strategy.createScheduler(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("thread pool scheduler configuration is null");
  }

  @Test
  @DisplayName("createScheduler when threadPool is null throws IllegalArgumentException")
  void createScheduler_nullThreadPool_throwsIllegalArgument() {
    SchedulerConfiguration config = new SchedulerConfiguration();

    assertThatThrownBy(() -> strategy.createScheduler(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("thread pool scheduler configuration is null");
  }

  private SchedulerConfiguration configWith(QueueType queueType) {
    SchedulerConfiguration config = new SchedulerConfiguration();
    config.setThreadPool(new ThreadPoolScheduler(2, 4, 100, false, 60, queueType));
    return config;
  }
}
