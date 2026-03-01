package com.nantaaditya.example.entity;

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
}
