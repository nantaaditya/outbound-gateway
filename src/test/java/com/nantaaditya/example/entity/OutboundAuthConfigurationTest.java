package com.nantaaditya.example.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboundAuthConfigurationTest {

  @Test
  @DisplayName("builder creates entity with all fields set")
  void builder_allFields_entityCreated() {
    LocalDateTime expiry = LocalDateTime.now().plusHours(1);

    OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
        .outboundSystemId(1L)
        .authType(AuthType.OAUTH)
        .clientId("client".getBytes())
        .clientSecret("secret".getBytes())
        .autoRefresh(true)
        .tokenMethod("POST")
        .tokenUrl("https://auth.example.com/token")
        .tokenBody("grant_type=client_credentials")
        .tokenPrefix("Bearer")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .tokenKeyName("Authorization")
        .tokenResponsePath("$.access_token")
        .tokenResponseExpiryPath("$.expires_in")
        .currentToken("some-token")
        .tokenExpiryTime(expiry)
        .build();

    assertThat(config.getOutboundSystemId()).isEqualTo(1L);
    assertThat(config.getAuthType()).isEqualTo(AuthType.OAUTH);
    assertThat(config.isAutoRefresh()).isTrue();
    assertThat(config.getTokenUrl()).isEqualTo("https://auth.example.com/token");
    assertThat(config.getTokenInjectLocation()).isEqualTo(TokenInjectLocation.HEADER);
    assertThat(config.getTokenExpiryTime()).isEqualTo(expiry);
    assertThat(config.getCurrentToken()).isEqualTo("some-token");
  }

  @Test
  @DisplayName("isNew returns true when id is null")
  void isNew_nullId_returnsTrue() {
    OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
        .outboundSystemId(1L)
        .authType(AuthType.NONE)
        .build();

    assertThat(config.isNew()).isTrue();
  }

  @Test
  @DisplayName("isNew returns false when id is set")
  void isNew_idSet_returnsFalse() {
    OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
        .id(42L)
        .outboundSystemId(1L)
        .authType(AuthType.NONE)
        .build();

    assertThat(config.isNew()).isFalse();
  }

  @Test
  @DisplayName("isTokenExpired returns true when expiry is in the past")
  void isTokenExpired_pastExpiry_returnsTrue() {
    OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
        .outboundSystemId(1L)
        .authType(AuthType.OAUTH)
        .tokenExpiryTime(LocalDateTime.now().minusMinutes(5))
        .build();

    assertThat(config.isTokenExpired()).isTrue();
  }

  @Test
  @DisplayName("isTokenExpired returns false when expiry is in the future")
  void isTokenExpired_futureExpiry_returnsFalse() {
    OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
        .outboundSystemId(1L)
        .authType(AuthType.OAUTH)
        .tokenExpiryTime(LocalDateTime.now().plusHours(1))
        .build();

    assertThat(config.isTokenExpired()).isFalse();
  }

  @Test
  @DisplayName("isTokenExpired returns true when expiry is null")
  void isTokenExpired_nullExpiry_returnsTrue() {
    OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
        .outboundSystemId(1L)
        .authType(AuthType.OAUTH)
        .tokenExpiryTime(null)
        .build();

    assertThat(config.isTokenExpired()).isTrue();
  }
}
