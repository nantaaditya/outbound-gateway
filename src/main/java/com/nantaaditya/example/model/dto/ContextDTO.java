package com.nantaaditya.example.model.dto;

import com.nantaaditya.example.helper.DateTimeHelper;
import com.nantaaditya.example.helper.TsidHelper;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.response.BaseResponse.ResponseMetadata;
import java.time.ZonedDateTime;
import java.util.Optional;
import lombok.Data;
import org.springframework.http.server.reactive.ServerHttpRequest;

@Data
public class ContextDTO {
  private String clientId;
  private String requestId;
  private String method;
  private String path;
  private String requestTime;
  private String receivedTime;
  private String responseCode;
  private String responseDescription;

  public void decorateContext(ServerHttpRequest request, String contextPath) {
    this.clientId = request.getHeaders().getFirst(HeaderConstant.CLIENT_ID.getHeader());
    this.requestId = request.getHeaders().getFirst(HeaderConstant.REQUEST_ID.getHeader());
    this.method = request.getMethod().name();
    this.path = request.getURI().getPath().replace(contextPath, "");
    this.requestTime = request.getHeaders().getFirst(HeaderConstant.REQUEST_TIME.getHeader());
    this.receivedTime = DateTimeHelper.getDateInFormat(ZonedDateTime.now(), DateTimeHelper.ISO_8601_GMT7_FORMAT);
  }

  public ContextDTO withResponse(ResponseMetadata responseMetadata) {
    this.responseCode = responseMetadata.getCode();
    this.responseDescription = responseMetadata.getDescription();
    return this;
  }

  public String getRequestId() {
    return Optional.ofNullable(requestId).orElse(TsidHelper.generateStringId());
  }

  public String getClientId() {
    return Optional.ofNullable(clientId).orElse("SYSTEM");
  }
}
