package com.nantaaditya.example.entity;

import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
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
}
