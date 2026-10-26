package com.nantaaditya.example.helper;

import com.nantaaditya.example.cache.ContextHelper;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.constant.ResponseCode;
import com.nantaaditya.example.model.response.BaseResponse;
import com.nantaaditya.example.model.response.BaseResponse.ErrorMetadata;
import com.nantaaditya.example.model.response.BaseResponse.PageMetadata;
import com.nantaaditya.example.model.response.BaseResponse.ResponseMetadata;
import com.nantaaditya.example.observability.TracerHelper;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ResponseHelper {
  private final TracerHelper tracerHelper;
  private final ContextHelper contextHelper;

  public <T> BaseResponse<T> success(T data) {
    ResponseMetadata responseMetadata = ResponseMetadata.builder()
        .code(ResponseCode.SUCCESS.getCode())
        .description(ResponseCode.SUCCESS.getMessage())
        .time(DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .build();

    String requestId = tracerHelper.getBaggage(HeaderConstant.REQUEST_ID);
    contextHelper.update(requestId, contextDTO ->
      contextDTO.withResponse(responseMetadata)
    );

    return BaseResponse.<T>builder()
        .response(responseMetadata)
        .data(data)
        .build();
  }

  public <T> BaseResponse<T> failed(ResponseCode responseCode, Map<String, List<String>> errors) {
    ResponseMetadata responseMetadata = ResponseMetadata.builder()
        .code(responseCode.getCode())
        .description(responseCode.getMessage())
        .time(DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .build();

    String requestId = tracerHelper.getBaggage(HeaderConstant.REQUEST_ID);
    contextHelper.update(requestId, contextDTO ->
        contextDTO.withResponse(responseMetadata)
    );

    return BaseResponse.<T>builder()
        .response(responseMetadata)
        .error(ErrorMetadata.builder()
            .violations(errors)
            .build()
        )
        .build();
  }

  public <T> BaseResponse<T> response(ResponseCode responseCode, T data, Map<String, List<String>> errors, PageMetadata pageMetadata) {
    ResponseMetadata responseMetadata = ResponseMetadata.builder()
        .code(responseCode.getCode())
        .description(responseCode.getMessage())
        .time(DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .build();

    String requestId = tracerHelper.getBaggage(HeaderConstant.REQUEST_ID);
    contextHelper.update(requestId, contextDTO ->
        contextDTO.withResponse(responseMetadata)
    );

    ErrorMetadata errorMetadata = null;
    if (errors != null && !errors.isEmpty()) {
      errorMetadata = ErrorMetadata.builder()
          .violations(errors)
          .build();
    }

    return BaseResponse.<T>builder()
        .response(responseMetadata)
        .data(data)
        .error(errorMetadata)
        .pagination(pageMetadata)
        .build();
  }
}
