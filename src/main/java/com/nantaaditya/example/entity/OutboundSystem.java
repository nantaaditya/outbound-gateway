package com.nantaaditya.example.entity;

import com.nantaaditya.example.model.request.admin.CreateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpdateOutboundSystemRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.http.HttpMethod;

@Data
@Table(name = "outbound_systems")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@SuppressWarnings("java:S1068")
public class OutboundSystem extends BaseEntity<Long> {
  private String code;
  private String name;
  private String description;
  private String httpMethod;
  private String baseUrl;
  private String apiPath;
  @Column("is_active")
  private boolean active;
  private int idempotencyTtlHours;

  public static OutboundSystem create(CreateOutboundSystemRequest request) {
    return OutboundSystem.builder()
        .code(request.code())
        .name(request.name())
        .description(request.description())
        .httpMethod(request.httpMethod())
        .baseUrl(request.baseUrl())
        .apiPath(request.apiPath())
        .active(request.active())
        .idempotencyTtlHours(request.idempotencyTtlHours())
        .build();
  }

  public void update(UpdateOutboundSystemRequest request) {
    this.setName(request.name());
    this.setDescription(request.description());
    this.setHttpMethod(request.httpMethod());
    this.setBaseUrl(request.baseUrl());
    this.setApiPath(request.apiPath());
    this.setActive(request.active());
    this.setIdempotencyTtlHours(request.idempotencyTtlHours());
  }

  public String getFullPath() {
    return baseUrl + apiPath;
  }

  public HttpMethod getHttpMethodEnum() {
    return HttpMethod.valueOf(httpMethod);
  }
}
