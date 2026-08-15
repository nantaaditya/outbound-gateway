package com.nantaaditya.example.service;

/**
 * Polls the dead-letter queue and exposes its depth as Micrometer gauges for the
 * central observability stack. Scheduled; never throws on failure.
 */
public interface DeadLetterQueueMetricsService {

  void pollDeadLetterQueue();
}
