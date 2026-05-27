package com.nantaaditya.example.entity;

import com.nantaaditya.example.helper.TsidHelper;
import com.nantaaditya.example.model.dto.ContextDTO;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table(name = "event_logs")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SuppressWarnings("java:S1068")
public class EventLog implements Persistable<String> {
  @Id
  private String id;
  private String clientId;
  private String requestId;
  private String method;
  private String path;
  private String responseCode;
  private String responseDescription;
  @Column
  private byte[] payload;
  @Column
  private byte[] additionalData;
  @CreatedDate
  private LocalDateTime createdDate;

  @Override
  public boolean isNew() {
    return true;
  }

  public static EventLog create(ContextDTO context, byte[] additionalData, String payload) {
    return EventLog.builder()
        .id(TsidHelper.generateStringId())
        .clientId(context.getClientId())
        .requestId(context.getRequestId())
        .method(context.getMethod())
        .path(context.getPath())
        .responseCode(context.getResponseCode())
        .responseDescription(context.getResponseDescription())
        .payload(payload.getBytes(StandardCharsets.UTF_8))
        .additionalData(additionalData)
        .createdDate(LocalDateTime.now())
        .build();
  }
}
