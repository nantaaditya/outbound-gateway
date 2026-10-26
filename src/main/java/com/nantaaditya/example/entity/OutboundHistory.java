package com.nantaaditya.example.entity;

import com.nantaaditya.example.model.response.GatewayResponse;
import java.time.LocalDateTime;
import java.util.Map;
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
public class OutboundHistory implements Persistable<Long> {

  @Id
  private Long id;
  private long outboundSystemId;
  private String idempotencyKey;
  private String callerService;
  private String requestId;
  private String requestMethod;
  private String requestUrl;
  private Map<String, Object> requestHeaders;
  private String requestPayload;
  private Integer responseStatus;
  private Map<String, Object> responseHeaders;
  private String responsePayload;
  private Integer durationMs;
  private Integer convertedStatus;
  private String convertedResponse;
  private int retryCount;
  /** SHA-256 of the client's request for rows stored under an idempotency key; null otherwise or for older rows. */
  private String requestFingerprint;
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

  public static OutboundHistory create(OutboundSystem system, String baseUrl, String callerService,
      String idempotencyKey, String requestId, String requestPayload,
      Map<String, Object> requestHeaders, GatewayResponse response,
      long durationMs, int retryCount, String requestFingerprint) {
    @SuppressWarnings("unchecked")
    Map<String, Object> responseHeaders = response.headers().isEmpty() ? null
        : (Map<String, Object>) (Map<?, ?>) response.headers();
    return OutboundHistory.builder()
        .outboundSystemId(system.getId())
        .idempotencyKey(idempotencyKey)
        .callerService(callerService)
        .requestId(requestId)
        .requestMethod(system.getHttpMethod())
        .requestUrl(system.getFullPath(baseUrl))
        .requestHeaders(requestHeaders)
        .requestPayload(requestPayload)
        .responseStatus(response.status())
        .responseHeaders(responseHeaders)
        .responsePayload(response.body())
        .durationMs((int) Math.min(durationMs, Integer.MAX_VALUE))
        .convertedStatus(response.convertedStatus())
        .convertedResponse(response.convertedResponse())
        .retryCount(retryCount)
        .requestFingerprint(requestFingerprint)
        .build();
  }
}
