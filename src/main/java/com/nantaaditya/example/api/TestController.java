package com.nantaaditya.example.api;

import com.nantaaditya.example.helper.ContextHelper;
import com.nantaaditya.example.helper.DateTimeHelper;
import com.nantaaditya.example.helper.TracerHelper;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.constant.ResponseCode;
import com.nantaaditya.example.model.dto.ContextDTO;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.response.BaseResponse.ResponseMetadata;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Log4j2
@RestController
public class TestController {

  @Autowired
  private TracerHelper tracerHelper;
  @Autowired
  private ContextHelper contextHelper;

  @GetMapping(
      value = "/api/test"
  )
  public Mono<Boolean> test() {
    return Mono.deferContextual(ctx -> {
      String requestId = tracerHelper.getBaggage(HeaderConstant.REQUEST_ID);

      if (requestId != null) {
        contextHelper.update(requestId, contextDTO -> contextDTO.withResponse(ResponseMetadata.builder()
            .code(ResponseCode.SUCCESS.getCode())
            .description(ResponseCode.SUCCESS.getMessage())
            .time(DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
            .build()));
        log.info(AppLogMessage.message("context: {}", contextHelper.get(requestId)));
      } else {
        log.warn(AppLogMessage.message("requestId is null"));
      }
      return Mono.just(Boolean.TRUE);
    });
  }

  @GetMapping(
      value = "/api/count"
  )
  public Mono<Integer> count() {
    return Mono.just(contextHelper.size());
  }

  @GetMapping(
      value = "/api/list"
  )
  public Mono<List<ContextDTO>> list() {
    return Mono.just(contextHelper.list());
  }
}
