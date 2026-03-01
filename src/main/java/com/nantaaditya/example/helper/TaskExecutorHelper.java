package com.nantaaditya.example.helper;

import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.properties.AsyncTaskProperties;
import com.nantaaditya.example.properties.embedded.AsyncConfiguration;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Log4j2
public class TaskExecutorHelper {

  private final BlockingQueue<Runnable> retryQueue;
  private final ScheduledExecutorService rejectedTaskScheduledExecutor;
  private final Executor executor;
  private final Semaphore rejectedTaskThrottler;

  private final int retryRejectedTaskBatchSize;

  @PreDestroy
  public void onDestroy() {
    rejectedTaskScheduledExecutor.close();
  }

  public TaskExecutorHelper(AsyncMDCTaskDecorator asyncMDCTaskDecorator,
      AsyncTaskProperties asyncTaskProperties) {
    this.retryQueue = new LinkedBlockingQueue<>(asyncTaskProperties.maxRetryRejectedTask());
    this.rejectedTaskThrottler = new Semaphore(asyncTaskProperties.maxRetryRejectedTask());

    this.executor = createExecutor(asyncTaskProperties.getConfiguration("rejected-task"), asyncMDCTaskDecorator);
    this.rejectedTaskScheduledExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
      Thread t = new Thread(task);
      t.setDaemon(true);
      t.setName("Retry-Rejected-Scheduler");
      return t;
    });

    this.retryRejectedTaskBatchSize = asyncTaskProperties.retryRejectedTaskBatchSize();
    executeRejectedTask(asyncTaskProperties.retryRejectedTaskInterval());
  }

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

    if (configuration.retryRejectedTaskEnabled()) {
      taskExecutor.setRejectedExecutionHandler((Runnable task, ThreadPoolExecutor executor) -> {
        log.warn(AppLogMessage.message("#AsyncExecutor - rejected execution of task {}", task));
        enqueue(task);
      });
    }
    taskExecutor.initialize();
    return taskExecutor;
  }

  public void enqueue(Runnable task) {
    try {
      rejectedTaskThrottler.acquire();
      if (retryQueue.offer(task)) {
        log.warn(AppLogMessage.message("Retry queue full — dropping task"));
      }
    } catch (Exception e) {
      log.error(AppLogMessage.message("#RetryExecutor - failed to re-enqueue task, error: {},", e.getMessage()).error(e));
      Thread.currentThread().interrupt();
    } finally {
      rejectedTaskThrottler.release();
    }
  }

  private void executeRejectedTask(int retryRejectedTaskInterval) {
    rejectedTaskScheduledExecutor.scheduleAtFixedRate(
        this::executeBatchRejectedTask,
        0,
        retryRejectedTaskInterval,
        TimeUnit.MILLISECONDS
    );
  }

  private void executeBatchRejectedTask() {
    List<Runnable> batch = new ArrayList<>(this.retryRejectedTaskBatchSize);
    retryQueue.drainTo(batch, this.retryRejectedTaskBatchSize);

    if (batch.isEmpty()) {
      log.info(AppLogMessage.message("#RetryExecutor - is empty"));
      return;
    }

    log.info(AppLogMessage.message("#RetryExecutor - running"));
    for (Runnable task : batch) {
      try {
        executor.execute(task);
      } catch (RejectedExecutionException e) {
        log.warn(AppLogMessage.message("#RetryExecutor - is full, re-queueing task"));
        enqueue(task);
      } catch (Exception e) {
        log.error(AppLogMessage.message("#RetryExecutor - failed executing retry task: {}", e.getMessage()).error(e));
      }
    }
    log.info(AppLogMessage.message("#RetryExecutor - done"));
  }
}
