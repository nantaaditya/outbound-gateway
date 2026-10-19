package com.nantaaditya.example.entity;

import com.nantaaditya.example.model.constant.ParameterInjectLocation;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Host-level key/value that a client can reference by {@code paramKey} in the gateway request
 * ({@code injectKeys}). The value is stored encrypted and never leaves the service via any API.
 */
@Data
@Table(name = "outbound_parameters")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@SuppressWarnings("java:S1068")
public class OutboundParameter extends BaseEntity<Long> {
  private long hostConfigurationId;
  private String paramKey;
  private byte[] paramValue;
  private ParameterInjectLocation injectLocation;
  private String targetName;
  @Column("is_active")
  private boolean active;

  public static OutboundParameter create(long hostConfigurationId, String paramKey,
      byte[] encryptedValue, ParameterInjectLocation injectLocation, String targetName,
      boolean active) {
    return OutboundParameter.builder()
        .hostConfigurationId(hostConfigurationId)
        .paramKey(paramKey)
        .paramValue(encryptedValue)
        .injectLocation(injectLocation)
        .targetName(targetName)
        .active(active)
        .build();
  }

  public OutboundParameter updated(byte[] encryptedValue, ParameterInjectLocation injectLocation,
      String targetName, boolean active) {
    return OutboundParameter.builder()
        .id(this.getId())
        .createdBy(this.getCreatedBy())
        .createdDate(this.getCreatedDate())
        .version(this.getVersion())
        .hostConfigurationId(this.hostConfigurationId)
        .paramKey(this.paramKey)
        .paramValue(encryptedValue)
        .injectLocation(injectLocation)
        .targetName(targetName)
        .active(active)
        .build();
  }
}
