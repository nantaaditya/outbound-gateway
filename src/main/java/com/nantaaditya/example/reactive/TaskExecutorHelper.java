package com.nantaaditya.example.reactive;

import com.nantaaditya.example.properties.embedded.AsyncConfiguration;
import java.util.concurrent.Executor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Log4j2
public class TaskExecutorHelper {

  public Executor createExecutor(AsyncConfiguration configuration,
      AsyncMDCTaskDecorator asyncMDCTaskDecorator) {
    ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
    taskExecutor.setThreadNamePrefix(configuration.threadNamePrefix());
    taskExecutor.setTaskDecorator(asyncMDCTaskDecorator);

    if (configuration.enableVirtualThread()) {
      taskExecutor.setVirtualThreads(configuration.enableVirtualThread());
    } else {
      taskExecutor.setCorePoolSize(configuration.corePoolSize());
      taskExecutor.setMaxPoolSize(configuration.maxPoolSize());
      taskExecutor.setQueueCapacity(configuration.queueCapacity());
      taskExecutor.setKeepAliveSeconds(configuration.keepAliveSeconds());
    }

    taskExecutor.initialize();
    return taskExecutor;
  }

}
