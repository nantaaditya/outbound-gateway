package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.entity.BaseEntity;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.model.exception.OutboundSystemNotFoundException;
import com.nantaaditya.example.model.response.GatewayHistoryResponse;
import com.nantaaditya.example.model.response.PageResponse;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.GatewayHistoryService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class GatewayHistoryServiceImpl implements GatewayHistoryService {

  private final OutboundHistoryRepository historyRepository;
  private final OutboundSystemRepository systemRepository;
  private final JsonHelper jsonHelper;

  // BIGSERIAL IDs start at 1, so 0 is a safe sentinel meaning "query across all systems".
  private static final long ALL_SYSTEMS_ID = 0L;

  @Override
  public Mono<PageResponse<GatewayHistoryResponse>> findHistories(
      @Nullable String code,
      @Nullable LocalDateTime startDate, @Nullable LocalDateTime endDate,
      @Nullable Integer status,
      int page, int size) {
    int safeSize = Math.max(1, Math.min(size, 100));
    int safePage = Math.max(0, page);
    int safeStatus = status != null ? status : 0;
    long offset = (long) safePage * safeSize;

    return resolveSystemId(code)
        .flatMap(systemId -> Mono.zip(
            historyRepository.countByFilters(systemId, safeStatus, startDate, endDate),
            historyRepository.findPageByFilters(systemId, safeStatus, startDate, endDate, safeSize, offset).collectList()
        ))
        .map(tuple -> {
          long total = tuple.getT1();
          List<GatewayHistoryResponse> content = tuple.getT2().stream()
              .map(h -> GatewayHistoryResponse.from(h, jsonHelper))
              .toList();
          int totalPages = safeSize > 0 ? (int) Math.ceil((double) total / safeSize) : 0;
          return new PageResponse<>(content, safePage, safeSize, total, totalPages);
        });
  }

  @Override
  public Mono<GatewayHistoryResponse> findByIdempotencyKey(String code, String idempotencyKey) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(Mono.error(new OutboundSystemNotFoundException(code)))
        .flatMap(system ->
            historyRepository.findFirstByOutboundSystemIdAndIdempotencyKeyOrderByCreatedDateDesc(system.getId(), idempotencyKey)
                .map(h -> GatewayHistoryResponse.from(h, jsonHelper))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("History not found for code=" + code)))
        );
  }

  private Mono<Long> resolveSystemId(@Nullable String code) {
    if (code == null || code.isBlank()) {
      return Mono.just(ALL_SYSTEMS_ID);
    }
    return systemRepository.findByCode(code)
        .map(BaseEntity::getId)
        .switchIfEmpty(Mono.error(new OutboundSystemNotFoundException(code)));
  }
}
