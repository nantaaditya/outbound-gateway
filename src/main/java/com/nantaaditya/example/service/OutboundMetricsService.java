package com.nantaaditya.example.service;

import com.nantaaditya.example.model.response.OutboundMetricsResponse;
import reactor.core.publisher.Mono;

public interface OutboundMetricsService {

  Mono<OutboundMetricsResponse> getMetrics(String code);
}
