package com.nantaaditya.example.entity;

import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.response.GatewayResponse;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table(name = "outbound_histories")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SuppressWarnings("java:S1068")
public class OutboundHistory implements Persistable<Long> {

  @Id
  private Long id;
  private long outboundSystemId;
  private String idempotencyKey;
  private String callerService;
  private String requestId;
  private String requestMethod;
  private String requestUrl;
  private String requestHeaders;
  private String requestPayload;
  private Integer responseStatus;
  private String responseHeaders;
  private String responsePayload;
  private Integer durationMs;
  private Integer convertedStatus;
  private String convertedResponse;
  private int retryCount;
  @CreatedDate
  private LocalDateTime createdDate;

  @Override
  public boolean isNew() {
    return id == null;
  }

  public boolean isSuccessful() {
    return responseStatus != null && responseStatus >= 200 && responseStatus < 300;
  }

  public boolean hasIdempotencyKey() {
    return idempotencyKey != null && !idempotencyKey.isBlank();
  }

  public static OutboundHistory create(OutboundSystem system, String callerService,
      String idempotencyKey, GatewayRequest request, GatewayResponse response, long durationMs,
      int retryCount, JsonHelper jsonHelper) {
    return OutboundHistory.builder()
        .outboundSystemId(system.getId())
        .idempotencyKey(idempotencyKey)
        .callerService(callerService)
        .requestMethod(system.getHttpMethod())
        .requestUrl(system.getBaseUrl() + system.getApiPath())
        .requestPayload(request.bodyAsString(jsonHelper))
        .responseStatus(response.status())
        .responsePayload(response.body())
        .durationMs((int) Math.min(durationMs, Integer.MAX_VALUE))
        .convertedStatus(response.convertedStatus())
        .convertedResponse(response.convertedResponse())
        .retryCount(retryCount)
        .build();
  }
}
