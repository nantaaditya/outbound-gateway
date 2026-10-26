package com.nantaaditya.example.reactive;

import java.util.concurrent.Executor;

public interface AsyncExecutorHelper {
  Executor getExecutor(String executorName);
  void execute(String executorName, Runnable task);
}
