package com.nantaaditya.example.api;

import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.helper.ResponseHelper;
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.response.BaseResponse;
import com.nantaaditya.example.model.response.GatewayExecuteResponse;
import com.nantaaditya.example.service.GatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/gateway")
@RequiredArgsConstructor
@Tag(name = "gateway api", description = "api for calling to outbound host")
public class GatewayController {

  private final GatewayService gatewayService;
  private final ResponseHelper responseHelper;
  private final JsonHelper jsonHelper;

  @PostMapping(
      value = "/execute/{code}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "call outbound host", description = "call outbound host")
  public Mono<BaseResponse<GatewayExecuteResponse>> execute(
      @PathVariable String code,
      @RequestHeader(value = ConstantName.CALLER_SERVICE_HEADER, required = false) String callerService,
      @RequestHeader(value = ConstantName.IDEMPOTENT_HEADER, required = false)
      @Size(max = ConstantName.IDEMPOTENCY_KEY_MAX_LENGTH, message = "NotValid")
      @Pattern(regexp = ConstantName.IDEMPOTENCY_KEY_PATTERN, message = "NotValid") String idempotencyKey,
      @RequestBody GatewayRequest request
  ) {
    return gatewayService.execute(code, callerService, idempotencyKey, request)
        .map(r -> GatewayExecuteResponse.from(r, jsonHelper))
        .map(responseHelper::success);
  }
}
