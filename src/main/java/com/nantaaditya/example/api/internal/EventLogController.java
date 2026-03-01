package com.nantaaditya.example.api.internal;

import com.nantaaditya.example.helper.ResponseHelper;
import com.nantaaditya.example.model.response.BaseResponse;
import com.nantaaditya.example.service.EventLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(value = "/internal-api/event_log")
@RequiredArgsConstructor
//@Tag(name = "internal api", description = "internal api for utility purpose")
public class EventLogController {

  private final EventLogService eventLogService;
  private final ResponseHelper responseHelper;

  @DeleteMapping(
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public Mono<BaseResponse<Boolean>> removeObsoleteEventLog(@RequestParam int days) {
    return eventLogService.remove(days)
      .map(responseHelper::success);
  }
}
