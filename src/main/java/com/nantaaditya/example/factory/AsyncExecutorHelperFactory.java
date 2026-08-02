package com.nantaaditya.example.factory;

import static com.nantaaditya.example.model.constant.ConstantName.ASYNC_POSTFIX_BEAN_NAME;

import com.nantaaditya.example.helper.AsyncExecutorHelper;
import com.nantaaditya.example.model.logger.AppLogMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.FactoryBean;

@Setter
public class AsyncExecutorHelperFactory implements FactoryBean<AsyncExecutorHelper> {

  private Map<String, Executor> executors = new HashMap<>();
  private Map<String, Semaphore> throttlers = new ConcurrentHashMap<>();

  @Override
  public AsyncExecutorHelper getObject() throws Exception {
    return new AsyncExecutorHelperImpl(executors, throttlers);
  }

  @Override
  public Class<?> getObjectType() {
    return AsyncExecutorHelper.class;
  }

  @Log4j2
  @AllArgsConstructor
  private static class AsyncExecutorHelperImpl implements AsyncExecutorHelper {

    private Map<String, Executor> executors = new HashMap<>();
    private Map<String, Semaphore> throttlers = new ConcurrentHashMap<>();

    @Override
    public Executor getExecutor(String executorName) {
      return executors.get(executorName + ASYNC_POSTFIX_BEAN_NAME);
    }

    @Override
    public void execute(String executorName, Runnable task) {
      Executor executor = getExecutor(executorName);
      Semaphore throttler = throttlers.get(executorName + ASYNC_POSTFIX_BEAN_NAME);

      if (executor != null) {
        executor.execute(() -> {
          try {
            if (throttler != null) throttler.acquire();
            task.run();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            if (throttler != null) throttler.release();
          }
        });
      } else {
        log.info(AppLogMessage.message("#Executor - {} not found", executorName));
      }
    }
  }
}
