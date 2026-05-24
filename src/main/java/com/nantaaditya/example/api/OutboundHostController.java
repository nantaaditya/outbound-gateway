package com.nantaaditya.example.api;

import com.nantaaditya.example.helper.ResponseHelper;
import com.nantaaditya.example.model.request.admin.CreateHostConfigRequest;
import com.nantaaditya.example.model.request.admin.UpdateHostConfigRequest;
import com.nantaaditya.example.model.response.BaseResponse;
import com.nantaaditya.example.model.response.OutboundHostConfigResponse;
import com.nantaaditya.example.service.OutboundHostService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/outbound/host")
@RequiredArgsConstructor
public class OutboundHostController {

  private final OutboundHostService outboundHostService;
  private final ResponseHelper responseHelper;

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public Mono<BaseResponse<OutboundHostConfigResponse>> create(
      @RequestBody @Valid CreateHostConfigRequest request) {
    return outboundHostService.create(request)
        .map(responseHelper::success);
  }

  @GetMapping(
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public Mono<BaseResponse<List<OutboundHostConfigResponse>>> list() {
    return outboundHostService.list()
        .map(responseHelper::success);
  }

  @GetMapping(
      value = "/{id}",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public Mono<BaseResponse<OutboundHostConfigResponse>> getById(@PathVariable long id) {
    return outboundHostService.getById(id)
        .map(responseHelper::success);
  }

  @PutMapping(
      value = "/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public Mono<BaseResponse<OutboundHostConfigResponse>> update(
      @PathVariable long id,
      @RequestBody @Valid UpdateHostConfigRequest request) {
    return outboundHostService.update(id, request)
        .map(responseHelper::success);
  }

  @PostMapping(
      value = "/{id}/reload",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public Mono<BaseResponse<Boolean>> reload(@PathVariable long id) {
    return outboundHostService.reload(id)
        .map(responseHelper::success);
  }
}
