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
}
