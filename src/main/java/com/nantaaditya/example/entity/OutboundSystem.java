package com.nantaaditya.example.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

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
}
