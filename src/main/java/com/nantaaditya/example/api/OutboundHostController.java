package com.nantaaditya.example.api;

import com.nantaaditya.example.helper.ResponseHelper;
import com.nantaaditya.example.model.request.admin.UpsertParametersRequest;
import com.nantaaditya.example.model.response.OutboundParameterResponse;
import com.nantaaditya.example.service.OutboundParameterService;
import com.nantaaditya.example.model.request.admin.CreateHostConfigRequest;
import com.nantaaditya.example.model.request.admin.UpdateHostConfigRequest;
import com.nantaaditya.example.model.response.BaseResponse;
import com.nantaaditya.example.model.response.OutboundHostConfigResponse;
import com.nantaaditya.example.model.response.OutboundSystemResponse;
import com.nantaaditya.example.service.OutboundHostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/outbound/host")
@RequiredArgsConstructor
@Tag(name = "outbound host api", description = "api to configure outbound host")
public class OutboundHostController {

  private final OutboundHostService outboundHostService;
  private final OutboundParameterService outboundParameterService;
  private final ResponseHelper responseHelper;

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "create outbound host", description = "create outbound host")
  public Mono<BaseResponse<OutboundHostConfigResponse>> create(
      @RequestBody @Valid CreateHostConfigRequest request) {
    return outboundHostService.create(request)
        .map(responseHelper::success);
  }

  @GetMapping(
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "list outbound host", description = "list outbound host")
  public Mono<BaseResponse<List<OutboundHostConfigResponse>>> list(
      @RequestParam(required = false, defaultValue = "0") @Min(value = 0, message = "NotValid") int page,
      @RequestParam(required = false, defaultValue = "10") @Min(value = 1, message = "NotValid") int size
  ) {
    return outboundHostService.list(page, size)
        .map(responseHelper::success);
  }

  @GetMapping(
      value = "/{id}",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "get outbound host by id", description = "get outbound host by id")
  public Mono<BaseResponse<OutboundHostConfigResponse>> getById(@PathVariable @Min(value = 1, message = "NotValid") long id) {
    return outboundHostService.getById(id)
        .map(responseHelper::success);
  }

  @PutMapping(
      value = "/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "update outbound host", description = "update outbound host")
  public Mono<BaseResponse<OutboundHostConfigResponse>> update(
      @PathVariable @Min(value = 1, message = "NotValid") long id,
      @RequestBody @Valid UpdateHostConfigRequest request) {
    return outboundHostService.update(id, request)
        .map(responseHelper::success);
  }

  @PostMapping(
      value = "/{id}/reload",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "reload outbound host", description = "reload outbound host")
  public Mono<BaseResponse<Boolean>> reload(@PathVariable @Min(value = 1, message = "NotValid") long id) {
    return outboundHostService.reload(id)
        .map(responseHelper::success);
  }

  @GetMapping(
      value = "/{id}/systems",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "list outbound system", description = "list outbound system")
  public Mono<BaseResponse<List<OutboundSystemResponse>>> listSystems(@PathVariable @Min(value = 1, message = "NotValid") long id) {
    return outboundHostService.listSystems(id)
        .map(responseHelper::success);
  }

  @PostMapping(
      value = "/{id}/parameters",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "upsert outbound host parameters",
      description = "store key-value parameters that clients reference with injectKeys; values are encrypted and never returned")
  public Mono<BaseResponse<List<OutboundParameterResponse>>> upsertParameters(
      @PathVariable @Min(value = 1, message = "NotValid") long id,
      @RequestBody @Valid UpsertParametersRequest request) {
    return outboundParameterService.upsert(id, request)
        .map(responseHelper::success);
  }

  @GetMapping(
      value = "/{id}/parameters",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "list outbound host parameters", description = "list parameters without their values")
  public Mono<BaseResponse<List<OutboundParameterResponse>>> listParameters(
      @PathVariable @Min(value = 1, message = "NotValid") long id) {
    return outboundParameterService.list(id)
        .map(responseHelper::success);
  }

  @DeleteMapping(
      value = "/{id}/parameters/{key}",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "delete outbound host parameter", description = "delete outbound host parameter by key")
  public Mono<BaseResponse<Boolean>> deleteParameter(
      @PathVariable @Min(value = 1, message = "NotValid") long id,
      @PathVariable String key) {
    return outboundParameterService.delete(id, key)
        .map(responseHelper::success);
  }
}
