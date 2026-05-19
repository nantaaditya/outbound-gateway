package com.nantaaditya.example.service;

import com.nantaaditya.example.model.response.GatewayHistoryResponse;
import com.nantaaditya.example.model.response.PageResponse;
import java.time.LocalDateTime;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;

public interface GatewayHistoryService {

  Mono<PageResponse<GatewayHistoryResponse>> findHistories(
      @Nullable String code,
      @Nullable LocalDateTime startDate,
      @Nullable LocalDateTime endDate,
      @Nullable Integer status,
      int page, int size);

  Mono<GatewayHistoryResponse> findByIdempotencyKey(String code, String idempotencyKey);
}
