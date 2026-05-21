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
}
