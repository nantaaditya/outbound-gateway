package com.nantaaditya.example.service;

import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.response.GatewayResponse;
import reactor.core.publisher.Mono;

public interface GatewayService {

  Mono<GatewayResponse> execute(
      String code,
      String callerService,
      String idempotencyKey,
      GatewayRequest request);

  /**
   * Evicts the cached context for the given outbound system code.
   * MUST NOT perform any I/O — this method is called from doOnSuccess callbacks
   * on Reactor scheduler threads where blocking is forbidden.
   */
  void evictContext(String code);

  /**
   * Evicts every cached context that uses the given host and recycles the host's connection pool.
   * Contexts hold a reference to the pooled client, so they must be dropped together with the pool.
   * MUST NOT perform any I/O (same rule as {@link #evictContext}).
   */
  void evictHost(String baseUrl);
}
