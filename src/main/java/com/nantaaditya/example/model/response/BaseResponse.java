package com.nantaaditya.example.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nantaaditya.example.helper.DateTimeHelper;
import com.nantaaditya.example.model.constant.ResponseCode;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@SuppressWarnings("java:S1068")
public class BaseResponse<T> {

  private ResponseMetadata response;
  private T data;
  private ErrorMetadata error;
  private PageMetadata pagination;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @SuppressWarnings("java:S1068")
  public static class ResponseMetadata {
    private String code;
    private String description;
    private String time;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @SuppressWarnings("java:S1068")
  public static class ErrorMetadata {
    private Map<String, List<String>> violations;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @SuppressWarnings("java:S1068")
  public static class PageMetadata {
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    public static PageMetadata from(PageResponse pagination) {
      return PageMetadata.builder()
          .page(pagination.page())
          .size(pagination.size())
          .totalElements(pagination.totalElements())
          .totalPages(pagination.totalPages())
          .build();
    }
  }

  public static <T> BaseResponse<T> failed(ResponseCode responseCode, Map<String, List<String>> errors) {
    return BaseResponse.<T>builder()
        .response(ResponseMetadata.builder()
            .code(responseCode.getCode())
            .description(responseCode.getMessage())
            .time(DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT))
            .build()
        )
        .error(ErrorMetadata.builder()
            .violations(errors)
            .build()
        )
        .build();
  }
}
