package com.nantaaditya.example.service;

import com.nantaaditya.example.properties.embedded.SchedulerConfiguration;
import reactor.core.scheduler.Scheduler;

public interface SchedulerStrategy {
  Scheduler createScheduler(SchedulerConfiguration schedulerConfiguration);
}
