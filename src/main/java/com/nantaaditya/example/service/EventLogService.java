package com.nantaaditya.example.service;

import reactor.core.publisher.Mono;

public interface EventLogService {
  Mono<Boolean> remove(int days);
}
