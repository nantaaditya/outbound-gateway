package com.nantaaditya.example.model.dto;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.springframework.web.reactive.function.client.WebClient;

@Getter
@Builder
public class OutboundSystemContext {

  private final OutboundSystem system;
  private final OutboundHostConfiguration hostConfig;
  private final OutboundResilienceConfiguration resilience;
  private final List<OutboundHeaderConfiguration> headers;
  private final OutboundAuthConfiguration auth;
  private final OutboundResponseMapperConfiguration responseMapper;
  private final WebClient webClient;
  private final CircuitBreaker circuitBreaker;
  private final Bulkhead bulkhead;
  private final RateLimiter rateLimiter;
  private final Retry retry;
}
