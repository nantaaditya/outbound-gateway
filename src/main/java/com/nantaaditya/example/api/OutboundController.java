package com.nantaaditya.example.api;

import com.nantaaditya.example.helper.ResponseHelper;
import com.nantaaditya.example.model.request.admin.CreateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpdateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpsertAuthRequest;
import com.nantaaditya.example.model.request.admin.UpsertHeadersRequest;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest;
import com.nantaaditya.example.model.request.admin.UpsertResponseMapperRequest;
import com.nantaaditya.example.model.response.BaseResponse;
import com.nantaaditya.example.model.response.OutboundMetricsResponse;
import com.nantaaditya.example.model.response.OutboundSystemDetailResponse;
import com.nantaaditya.example.model.response.OutboundSystemResponse;
import com.nantaaditya.example.model.response.PageResponse;
import com.nantaaditya.example.model.response.TestConnectionResponse;
import com.nantaaditya.example.service.OutboundMetricsService;
import com.nantaaditya.example.service.OutboundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/outbound")
@RequiredArgsConstructor
@Tag(name = "outbound api", description = "api to get outbound gateway history")
public class OutboundController {
  private final OutboundService outboundService;
  private final OutboundMetricsService outboundMetricsService;
  private final ResponseHelper responseHelper;

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "create outbound system", description = "create outbound system")
  public Mono<BaseResponse<OutboundSystemResponse>> create(
      @RequestBody @Valid CreateOutboundSystemRequest request) {
    return outboundService.create(request)
        .map(responseHelper::success);
  }

  @GetMapping(
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "list outbound system", description = "list outbound system")
  public Mono<BaseResponse<PageResponse<OutboundSystemDetailResponse>>> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return outboundService.list(page, size)
        .map(responseHelper::success);
  }

  @GetMapping(
      value = "/{code}",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "get outbound system", description = "get outbound system")
  public Mono<BaseResponse<OutboundSystemDetailResponse>> getDetail(
      @PathVariable String code) {
    return outboundService.getDetail(code)
        .map(responseHelper::success);
  }

  @PutMapping(
      value = "/{code}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "update outbound system",
      description = "update outbound system; reload=true (default) applies it to the running gateway at once, "
          + "reload=false only stores it until the reload endpoint is called")
  public Mono<BaseResponse<OutboundSystemResponse>> update(
      @PathVariable String code,
      @RequestParam(defaultValue = "true") boolean reload,
      @RequestBody @Valid UpdateOutboundSystemRequest request) {
    return outboundService.update(code, request, reload)
        .map(responseHelper::success);
  }

  @DeleteMapping(
      value = "/{code}",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "delete outbound system", description = "delete outbound system")
  public Mono<BaseResponse<Boolean>> delete(@PathVariable String code) {
    return outboundService.delete(code)
        .map(responseHelper::success);
  }

  @PostMapping(
      value = "/{code}/resilience",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "update outbound system resilience configuration", description = "update outbound system resilience configuration")
  public Mono<BaseResponse<Boolean>> upsertResilience(
      @PathVariable String code,
      @RequestBody @Valid UpsertResilienceRequest request) {
    return outboundService.upsertResilience(code, request)
        .map(responseHelper::success);
  }

  @PostMapping(
      value = "/{code}/headers",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "update outbound system headers configuration", description = "update outbound system headers configuration")
  public Mono<BaseResponse<Boolean>> upsertHeaders(
      @PathVariable String code,
      @RequestBody @Valid UpsertHeadersRequest request) {
    return outboundService.upsertHeaders(code, request)
        .map(responseHelper::success);
  }

  @PostMapping(
      value = "/{code}/authentication",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "update outbound system auth configuration", description = "update outbound system auth configuration")
  public Mono<BaseResponse<Boolean>> upsertAuth(
      @PathVariable String code,
      @RequestBody @Valid UpsertAuthRequest request) {
    return outboundService.upsertAuth(code, request)
        .map(responseHelper::success);
  }

  @PostMapping(
      value = "/{code}/response-mapper",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "update outbound system response mapper configuration", description = "update outbound system response mapper configuration")
  public Mono<BaseResponse<Boolean>> upsertResponseMapper(
      @PathVariable String code,
      @RequestBody @Valid UpsertResponseMapperRequest request) {
    return outboundService.upsertResponseMapper(code, request)
        .map(responseHelper::success);
  }

  @PostMapping(
      value = "/{code}/reload",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "reload outbound system configuration", description = "reload outbound system configuration")
  public Mono<BaseResponse<Boolean>> reload(@PathVariable String code) {
    return outboundService.reload(code)
        .map(responseHelper::success);
  }

  @PostMapping(
      value = "/{code}/test",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "test outbound system configuration", description = "test outbound system configuration")
  public Mono<BaseResponse<TestConnectionResponse>> testConnection(
      @PathVariable String code) {
    return outboundService.testConnection(code)
        .map(responseHelper::success);
  }

  @GetMapping(
      value = "/{code}/metrics",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "get outbound system metrics", description = "get outbound system metrics")
  public Mono<BaseResponse<OutboundMetricsResponse>> getMetrics(
      @PathVariable String code) {
    return outboundMetricsService.getMetrics(code)
        .map(responseHelper::success);
  }
}
