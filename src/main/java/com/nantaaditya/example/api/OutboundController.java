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
import com.nantaaditya.example.model.response.TestConnectionResponse;
import com.nantaaditya.example.service.OutboundMetricsService;
import com.nantaaditya.example.service.OutboundService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/outbound")
@RequiredArgsConstructor
public class OutboundController {
  private final OutboundService outboundService;
  private final OutboundMetricsService outboundMetricsService;
  private final ResponseHelper responseHelper;

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public Mono<BaseResponse<OutboundSystemResponse>> create(
      @RequestBody @Valid CreateOutboundSystemRequest request) {
    return outboundService.create(request)
        .map(responseHelper::success);
  }

  @GetMapping(
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public Mono<BaseResponse<List<OutboundSystemResponse>>> list() {
    return outboundService.list()
        .map(responseHelper::success);
  }

  @GetMapping(
      value = "/{code}",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
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
  public Mono<BaseResponse<OutboundSystemResponse>> update(
      @PathVariable String code,
      @RequestBody @Valid UpdateOutboundSystemRequest request) {
    return outboundService.update(code, request)
        .map(responseHelper::success);
  }

  @DeleteMapping(
      value = "/{code}",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public Mono<BaseResponse<Boolean>> delete(@PathVariable String code) {
    return outboundService.delete(code)
        .map(responseHelper::success);
  }

  @PostMapping(
      value = "/{code}/resilience",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
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
  public Mono<BaseResponse<Boolean>> upsertHeaders(
      @PathVariable String code,
      @RequestBody @Valid UpsertHeadersRequest request) {
    return outboundService.upsertHeaders(code, request)
        .map(responseHelper::success);
  }

  @PostMapping(
      value = "/{code}/auth",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
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
  public Mono<BaseResponse<Boolean>> reload(@PathVariable String code) {
    return outboundService.reload(code)
        .map(responseHelper::success);
  }

  @PostMapping(
      value = "/{code}/test",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public Mono<BaseResponse<TestConnectionResponse>> testConnection(
      @PathVariable String code) {
    return outboundService.testConnection(code)
        .map(responseHelper::success);
  }

  @GetMapping(
      value = "/{code}/metrics",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public Mono<BaseResponse<OutboundMetricsResponse>> getMetrics(
      @PathVariable String code) {
    return outboundMetricsService.getMetrics(code)
        .map(responseHelper::success);
  }
}
