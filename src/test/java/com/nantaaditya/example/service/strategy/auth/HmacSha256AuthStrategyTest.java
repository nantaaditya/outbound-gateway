package com.nantaaditya.example.service.strategy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class HmacSha256AuthStrategyTest {

  private HmacSha256AuthStrategy strategy;
  private EncryptionHelper encryptionHelper;

  @BeforeEach
  void setUp() {
    encryptionHelper = new EncryptionHelper(Base64.getEncoder().encodeToString(new byte[32]));
    strategy = new HmacSha256AuthStrategy();
  }

  @Test
  @DisplayName("supports returns HMAC")
  void supports_returnsHmac() {
    assertThat(strategy.supports()).isEqualTo(AuthType.HMAC);
  }

  @Test
  @DisplayName("authenticate with non-null body returns Base64-encoded HMAC credential")
  void authenticate_withBody_returnsHmacCredential() {
    OutboundAuthConfiguration config = configWith("my-secret");

    StepVerifier.create(AuthTestSupport.single(strategy, config, encryptionHelper, "{\"amount\":100}"))
        .assertNext(cred -> {
          assertThat(cred.keyName()).isEqualTo("X-Signature");
          assertThat(cred.location()).isEqualTo(TokenInjectLocation.HEADER);
          assertThat(isValidBase64(cred.value())).isTrue();
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("authenticate with null body signs empty string and returns valid Base64 HMAC")
  void authenticate_nullBody_signsEmptyString() {
    OutboundAuthConfiguration config = configWith("some-secret");

    StepVerifier.create(AuthTestSupport.single(strategy, config, encryptionHelper, null))
        .assertNext(cred -> assertThat(isValidBase64(cred.value())).isTrue())
        .verifyComplete();
  }

  @Test
  @DisplayName("same body and secret produce identical signatures on repeated calls")
  void authenticate_repeatCall_producesSameSignature() {
    OutboundAuthConfiguration config = configWith("stable-secret");
    String body = "{\"key\":\"value\"}";

    var first = AuthTestSupport.single(strategy, config, encryptionHelper, body).block();
    var second = AuthTestSupport.single(strategy, config, encryptionHelper, body).block();

    assertThat(first).isNotNull();
    assertThat(first.value()).isEqualTo(second.value());
  }

  @Test
  @DisplayName("different bodies with the same secret produce different signatures")
  void authenticate_differentBodies_produceDifferentSignatures() {
    OutboundAuthConfiguration config = configWith("stable-secret");

    var first = AuthTestSupport.single(strategy, config, encryptionHelper, "body-a").block();
    var second = AuthTestSupport.single(strategy, config, encryptionHelper, "body-b").block();

    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(first.value()).isNotEqualTo(second.value());
  }

  @Test
  @DisplayName("different secrets with the same body produce different signatures")
  void authenticate_differentSecrets_produceDifferentSignatures() {
    OutboundAuthConfiguration config1 = configWith("secret-a");
    OutboundAuthConfiguration config2 = configWith("secret-b");
    String body = "common-body";

    var first = AuthTestSupport.single(strategy, config1, encryptionHelper, body).block();
    var second = AuthTestSupport.single(strategy, config2, encryptionHelper, body).block();

    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(first.value()).isNotEqualTo(second.value());
  }

  private OutboundAuthConfiguration configWith(String secret) {
    byte[] encrypted = encryptionHelper.encryptString(secret);
    return OutboundAuthConfiguration.builder()
        .clientSecret(encrypted)
        .tokenKeyName("X-Signature")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build();
  }

  private boolean isValidBase64(String s) {
    try {
      Base64.getDecoder().decode(s);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
