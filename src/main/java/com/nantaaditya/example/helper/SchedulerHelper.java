package com.nantaaditya.example.helper;

import reactor.core.scheduler.Scheduler;

public interface SchedulerHelper {
  Scheduler from(String name);
}
