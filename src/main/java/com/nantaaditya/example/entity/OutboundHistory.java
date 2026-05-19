package com.nantaaditya.example.entity;

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
}
