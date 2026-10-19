package com.nantaaditya.example.service;

import reactor.core.publisher.Mono;

/**
 * Retention operations on {@code outbound_histories}. Reading histories is {@link GatewayHistoryService}.
 */
public interface OutboundHistoryService {

  /**
   * Schedules deletion of histories created more than {@code days} days ago and returns immediately;
   * the delete runs as a background task, so failures are only logged.
   */
  Mono<Boolean> remove(int days);
}
