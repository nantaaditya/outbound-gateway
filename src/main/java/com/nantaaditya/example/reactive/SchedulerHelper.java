package com.nantaaditya.example.reactive;

import reactor.core.scheduler.Scheduler;

public interface SchedulerHelper {
  Scheduler from(String name);
}
