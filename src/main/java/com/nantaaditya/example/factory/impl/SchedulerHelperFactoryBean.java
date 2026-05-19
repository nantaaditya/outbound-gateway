package com.nantaaditya.example.factory.impl;

import com.nantaaditya.example.helper.SchedulerHelper;
import com.nantaaditya.example.model.constant.SchedulerType;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.properties.SchedulerProperties;
import com.nantaaditya.example.properties.embedded.SchedulerConfiguration;
import com.nantaaditya.example.service.SchedulerStrategy;
import com.nantaaditya.example.service.strategy.schedulers.BounderElasticSchedulerStrategy;
import com.nantaaditya.example.service.strategy.schedulers.ExecutorSchedulerStrategy;
import com.nantaaditya.example.service.strategy.schedulers.ParallelSchedulerStrategy;
import com.nantaaditya.example.service.strategy.schedulers.SingleSchedulerStrategy;
import com.nantaaditya.example.service.strategy.schedulers.ThreadPoolSchedulerStrategy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.FactoryBean;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Log4j2
public class SchedulerHelperFactoryBean implements FactoryBean<SchedulerHelper> {

  private final SchedulerProperties schedulerProperties;
  private Map<SchedulerType, SchedulerStrategy> schedulerStrategies = new HashMap<>();
  
  public SchedulerHelperFactoryBean(SchedulerProperties schedulerProperties) {
    this.schedulerProperties = schedulerProperties;
    loadSchedulerStrategies();
  }

  private void loadSchedulerStrategies() {
    for (SchedulerType schedulerType : SchedulerType.values()) {
      SchedulerStrategy schedulerStrategy = switch (schedulerType) {
        case SINGLE -> new SingleSchedulerStrategy();
        case PARALLEL ->  new ParallelSchedulerStrategy();
        case BOUNDED_ELASTIC -> new BounderElasticSchedulerStrategy();
        case EXECUTOR ->  new ExecutorSchedulerStrategy();
        case THREAD_POOL ->   new ThreadPoolSchedulerStrategy();
      };
      schedulerStrategies.put(schedulerType, schedulerStrategy);
    }
  }

  @Override
  public Class<?> getObjectType() {
    return SchedulerHelper.class;
  }

  @Override
  public SchedulerHelper getObject() throws Exception {
    if (schedulerProperties.configurations() == null) {
      return new SchedulerHelperImpl(Collections.emptyMap());
    }

    Map<String, Scheduler> schedulers = new HashMap<>();
    schedulerProperties.configurations()
        .forEach((name, configurations) ->
            schedulers.put(name, buildScheduler(name, configurations))
        );

    return new SchedulerHelperImpl(schedulers);
  }

  @RequiredArgsConstructor
  private static class SchedulerHelperImpl implements SchedulerHelper {

    private final Map<String, Scheduler> schedulers;

    @Override
    public Scheduler from(String name) {
      return schedulers.getOrDefault(name, Schedulers.immediate());
    }
  }

  private Scheduler buildScheduler(String name, SchedulerConfiguration configuration) {
    Scheduler scheduler = null;
    try {
      SchedulerStrategy schedulerStrategy = schedulerStrategies.get(configuration.getSchedulerType());
      scheduler = schedulerStrategy.createScheduler(configuration);
    } catch (Exception e) {
      log.error(AppLogMessage.message("#Scheduler - failed to build scheduler {}, error {}",
          name, e.getMessage()).error(e));
    } finally {
      return scheduler;
    }
  }
}
