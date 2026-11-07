package com.nantaaditya.example.configuration;

import com.nantaaditya.example.factory.AsyncExecutorHelperFactory;
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.properties.AsyncTaskProperties;
import com.nantaaditya.example.properties.embedded.AsyncConfiguration;
import com.nantaaditya.example.reactive.AsyncMDCTaskDecorator;
import com.nantaaditya.example.reactive.TaskExecutorHelper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Log4j2
@Configuration
@RequiredArgsConstructor
public class AsyncTaskConfiguration {

  private final AsyncTaskProperties asyncProperties;
  private final ReactiveWebServerApplicationContext applicationContext;

  @Bean
  public AsyncExecutorHelperFactory asyncExecutorHelperFactory() {
    Map<String, Executor> executors = new HashMap<>();
    Map<String, Semaphore> throttlers = new ConcurrentHashMap<>();
    AsyncExecutorHelperFactory factory = new AsyncExecutorHelperFactory();
    AsyncMDCTaskDecorator asyncMDCTaskDecorator = new AsyncMDCTaskDecorator();
    TaskExecutorHelper taskExecutorHelper = new TaskExecutorHelper();

    if (asyncProperties.configurations() == null || asyncProperties.configurations().isEmpty()) {
      log.warn(AppLogMessage.message("#AsyncExecutor - no bean defined"));
      factory.setExecutors(executors);
      factory.setThrottlers(throttlers);
      return factory;
    }

    asyncProperties.configurations()
        .forEach((key, value) -> {
              AsyncConfiguration configuration = asyncProperties.getConfiguration(key);
              Executor taskExecutor = taskExecutorHelper.createExecutor(configuration, asyncMDCTaskDecorator);
              registerExecutorBean(key, taskExecutor);
              executors.put(key + ConstantName.ASYNC_POSTFIX_BEAN_NAME, taskExecutor);
              throttlers.put(key + ConstantName.ASYNC_POSTFIX_BEAN_NAME, new Semaphore(configuration.getThrottlerSize()));
            }
        );

    log.debug(AppLogMessage.message("#AsyncExecutor - bean {} created", asyncProperties.getBeanNames(ConstantName.ASYNC_POSTFIX_BEAN_NAME)));
    factory.setExecutors(executors);
    factory.setThrottlers(throttlers);
    return factory;
  }

  private void registerExecutorBean(String key, Executor taskExecutor) {
    applicationContext.registerBean(
        key + ConstantName.ASYNC_POSTFIX_BEAN_NAME,
        Executor.class,
        () -> taskExecutor,
        definition -> definition.setLazyInit(true)
    );
  }
}