package com.nantaaditya.example.api;

import com.nantaaditya.example.helper.ResponseHelper;
import com.nantaaditya.example.model.response.BaseResponse;
import com.nantaaditya.example.model.response.GatewayHistoryResponse;
import com.nantaaditya.example.model.response.PageResponse;
import com.nantaaditya.example.service.GatewayHistoryService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/gateway")
@RequiredArgsConstructor
public class GatewayHistoryController {

  private final GatewayHistoryService gatewayHistoryService;
  private final ResponseHelper responseHelper;

  @GetMapping(value = "/histories",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<BaseResponse<PageResponse<GatewayHistoryResponse>>> findHistories(
      @RequestParam(required = false) @Nullable String code,
      @RequestParam(required = false) @Nullable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
      @RequestParam(required = false) @Nullable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
      @RequestParam(required = false) @Nullable Integer status,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return gatewayHistoryService.findHistories(code, startDate, endDate, status, page, size)
        .map(responseHelper::success);
  }

  @GetMapping(value = "/histories/{idempotencyKey}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public Mono<BaseResponse<GatewayHistoryResponse>> findByIdempotencyKey(
      @PathVariable String idempotencyKey, @RequestParam String code) {
    return gatewayHistoryService.findByIdempotencyKey(code, idempotencyKey)
        .map(responseHelper::success);
  }
}
