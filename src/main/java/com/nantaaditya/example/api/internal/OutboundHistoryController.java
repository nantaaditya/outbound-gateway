package com.nantaaditya.example.api.internal;

import com.nantaaditya.example.helper.ResponseHelper;
import com.nantaaditya.example.model.response.BaseResponse;
import com.nantaaditya.example.service.OutboundHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(value = "/internal-api/outbound_history")
@RequiredArgsConstructor
@Tag(name = "internal api", description = "internal api for utility purpose")
public class OutboundHistoryController {

  private final OutboundHistoryService outboundHistoryService;
  private final ResponseHelper responseHelper;

  // days must be positive: days=0 would purge every history, including today's idempotency records
  @DeleteMapping(
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @Operation(summary = "remove obsolete outbound history",
      description = "remove outbound histories older than the given number of days (runs in background)")
  public Mono<BaseResponse<Boolean>> removeObsoleteOutboundHistory(@RequestParam @Min(1) int days) {
    return outboundHistoryService.remove(days)
        .map(responseHelper::success);
  }
}
