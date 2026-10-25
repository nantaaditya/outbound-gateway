package com.nantaaditya.example.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.request.admin.UpsertAuthRequest;
import java.beans.Transient;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table(name = "outbound_auth_configurations")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@SuppressWarnings("java:S1068")
public class OutboundAuthConfiguration extends BaseEntity<Long> {

  private long outboundSystemId;
  private AuthType authType;
  private byte[] apiKey;
  private byte[] clientId;
  private byte[] clientSecret;
  @Column("is_auto_refresh")
  private boolean autoRefresh;
  private String tokenMethod;
  private String tokenUrl;
  private String tokenBody;
  private String tokenPrefix;
  private TokenInjectLocation tokenInjectLocation;
  private String tokenKeyName;
  private String tokenResponsePath;
  private String tokenResponseExpiryPath;
  private String currentToken;
  private LocalDateTime tokenExpiryTime;
  /** Non-secret, per-type settings (for example {@code timestampHeader} of HMAC_TIMESTAMP); secrets stay encrypted above. */
  private Map<String, Object> authSettings;

  public boolean isTokenExpired() {
    return tokenExpiryTime == null || LocalDateTime.now().isAfter(tokenExpiryTime);
  }
  
  public OutboundAuthConfiguration updated(long outboundSystemId, byte[] apiKey, byte[] clientId, byte[] clientSecret, UpsertAuthRequest request) {
    return OutboundAuthConfiguration.builder()
        .id(this.getId())
        .createdBy(this.getCreatedBy())
        .createdDate(this.getCreatedDate())
        .version(this.getVersion())
        .outboundSystemId(outboundSystemId)
        .authType(request.authType())
        .apiKey(apiKey)
        .clientId(clientId)
        .clientSecret(clientSecret)
        .autoRefresh(request.autoRefresh())
        .tokenMethod(request.tokenMethod())
        .tokenUrl(request.tokenUrl())
        .tokenBody(request.tokenBody())
        .tokenPrefix(request.tokenPrefix())
        .tokenInjectLocation(request.tokenInjectLocation())
        .tokenKeyName(request.tokenKeyName())
        .tokenResponsePath(request.tokenResponsePath())
        .tokenResponseExpiryPath(request.tokenResponseExpiryPath())
        .authSettings(request.authSettings() == null ? null : new LinkedHashMap<>(request.authSettings()))
        .currentToken(this.currentToken)
        .tokenExpiryTime(this.tokenExpiryTime)
        .build();
  }

  @Transient
  @JsonIgnore
  public boolean isPrefixNotBlank() {
    return tokenPrefix != null && !tokenPrefix.isBlank();
  }

  public static OutboundAuthConfiguration empty(long outboundSystemId) {
    return OutboundAuthConfiguration.builder()
        .outboundSystemId(outboundSystemId)
        .authType(AuthType.NONE)
        .build();
  }
}
