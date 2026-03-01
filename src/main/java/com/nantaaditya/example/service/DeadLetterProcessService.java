package com.nantaaditya.example.service;

import com.nantaaditya.example.model.request.RetryDeadLetterProcessRequest;
import reactor.core.publisher.Mono;

public interface DeadLetterProcessService {
  Mono<Boolean> remove(int days);
  Mono<Boolean> retry(RetryDeadLetterProcessRequest request);
}
