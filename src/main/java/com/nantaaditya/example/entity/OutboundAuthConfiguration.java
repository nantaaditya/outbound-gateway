package com.nantaaditya.example.entity;

import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.request.admin.UpsertAuthRequest;
import java.time.LocalDateTime;
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

  public boolean isTokenExpired() {
    return tokenExpiryTime == null || LocalDateTime.now().isAfter(tokenExpiryTime);
  }
  
  public void update(long outboundSystemId, byte[] apiKey, byte[] clientId, byte[] clientSecret, UpsertAuthRequest request) {
    this.setOutboundSystemId(outboundSystemId);
    this.setAuthType(request.authType());
    this.setApiKey(apiKey);
    this.setClientId(clientId);
    this.setClientSecret(clientSecret);
    this.setAutoRefresh(request.autoRefresh());
    this.setTokenMethod(request.tokenMethod());
    this.setTokenUrl(request.tokenUrl());
    this.setTokenBody(request.tokenBody());
    this.setTokenPrefix(request.tokenPrefix());
    this.setTokenInjectLocation(request.tokenInjectLocation());
    this.setTokenKeyName(request.tokenKeyName());
    this.setTokenResponsePath(request.tokenResponsePath());
    this.setTokenResponseExpiryPath(request.tokenResponseExpiryPath());
  }
}
