package com.nantaaditya.example.api;

import com.nantaaditya.example.helper.ResponseHelper;
import com.nantaaditya.example.model.constant.ResponseCode;
import com.nantaaditya.example.model.response.BaseResponse;
import com.nantaaditya.example.model.response.BaseResponse.PageMetadata;
import com.nantaaditya.example.model.response.GatewayHistoryResponse;
import com.nantaaditya.example.service.GatewayHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/gateway")
@RequiredArgsConstructor
@Validated
@Tag(name = "gateway history api", description = "api to get outbound gateway history")
public class GatewayHistoryController {

  private final GatewayHistoryService gatewayHistoryService;
  private final ResponseHelper responseHelper;

  @GetMapping(value = "/histories",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "find outbound gateway histories", description = "find outbound gateway histories")
  public Mono<BaseResponse<List<GatewayHistoryResponse>>> findHistories(
      @RequestParam(required = false) @Nullable String code,
      @RequestParam(required = false) @Nullable @DateTimeFormat(iso = ISO.DATE) LocalDate startDate,
      @RequestParam(required = false) @Nullable @DateTimeFormat(iso = ISO.DATE) LocalDate endDate,
      @RequestParam(required = false) @Nullable Integer status,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return gatewayHistoryService.findHistories(code, startDate, endDate, status, page, size)
        .map(pageResponse -> responseHelper
            .response(ResponseCode.SUCCESS, pageResponse.content(), null, PageMetadata.from(pageResponse)));
  }

  @GetMapping(value = "/history/{code}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "find outbound gateway idempotency histories", description = "find outbound gateway idempotency histories")
  public Mono<BaseResponse<GatewayHistoryResponse>> findByIdempotencyKey(
      @RequestParam(required = true) @NotBlank(message = "NotBlank") String idempotencyKey,
      @PathVariable String code) {
    return gatewayHistoryService.findByIdempotencyKey(code, idempotencyKey)
        .map(responseHelper::success);
  }
}
