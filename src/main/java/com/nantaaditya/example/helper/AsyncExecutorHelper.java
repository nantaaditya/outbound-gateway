package com.nantaaditya.example.helper;

import java.util.concurrent.Executor;

public interface AsyncExecutorHelper {
  Executor getExecutor(String executorName);
  void execute(String executorName, Runnable task);
}
