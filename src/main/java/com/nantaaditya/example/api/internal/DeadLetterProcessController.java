package com.nantaaditya.example.api.internal;

import com.nantaaditya.example.helper.ResponseHelper;
import com.nantaaditya.example.model.request.RetryDeadLetterProcessRequest;
import com.nantaaditya.example.model.response.BaseResponse;
import com.nantaaditya.example.service.DeadLetterProcessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(value = "/internal-api/dead_letter_process")
@RequiredArgsConstructor
@Tag(name = "internal api", description = "internal api for utility purpose")
public class DeadLetterProcessController {

  private final DeadLetterProcessService deadLetterProcessService;
  private final ResponseHelper responseHelper;

  @DeleteMapping(
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "remove obsolete dead letter process", description = "remove obsolete dead letter process")
  public Mono<BaseResponse<Boolean>> removeObsoleteDeadLetterProcess(@RequestParam int days) {
    return deadLetterProcessService.remove(days)
      .map(responseHelper::success);
  }

  @PutMapping(
      value = "/_retry",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "retry dead letter process group", description = "retry dead letter process group")
  public Mono<BaseResponse<Boolean>> retry(@RequestBody @Valid RetryDeadLetterProcessRequest request) {
    return deadLetterProcessService.retry(request)
      .map(responseHelper::success);
  }
}
