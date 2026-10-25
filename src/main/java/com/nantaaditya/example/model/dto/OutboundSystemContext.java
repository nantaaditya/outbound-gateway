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

/**
 * Everything needed to call one outbound system, resolved once and cached (see
 * {@code gateway.SystemContextLoader}) and then shared by all concurrent calls of that system.
 *
 * <p><b>Treat every entity in here as read-only.</b> The entities are mutable Lombok {@code @Data} classes, but
 * nothing may call a setter on them: the same instances are read by many threads, so a change would be visible
 * mid-call and would never reach the database. Configuration changes go through the admin API, which writes a
 * copy ({@code entity.updated(...)}) and evicts this context so the next call loads a fresh one. Per-call or
 * per-token state belongs in the component that needs it (e.g. the OAuth strategy keeps its token in an
 * immutable record), not in these entities.
 */
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
