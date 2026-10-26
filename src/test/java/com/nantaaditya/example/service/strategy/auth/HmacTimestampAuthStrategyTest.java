package com.nantaaditya.example.service.strategy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.SigningInput;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * HMAC_TIMESTAMP: {@code signature = Base64(HMAC-SHA256(secret, timestamp + "." + body))}, with the timestamp as an
 * ISO-8601 UTC instant in whole seconds. The expected values were computed outside this code base (Python
 * {@code hmac}), so a verifier written from the documented rule agrees with them.
 */
class HmacTimestampAuthStrategyTest {

  private static final String TIMESTAMP = "2026-10-26T08:00:00Z";
  private static final String BODY = "{\"amount\":100,\"txId\":\"tx-1\"}";
  private static final String SIGNATURE_OF_BODY = "PsDzncjr+TNDXZwtw7Z4n/Gg+m1JwRGi5WbGf20CRb8=";
  private static final String SIGNATURE_OF_EMPTY = "58aOq/OqHXgE3vlwYuftjunMRqs0k9HCU10TT7yro58=";

  private final EncryptionHelper encryption = new EncryptionHelper(Base64.getEncoder().encodeToString(new byte[32]));
  // 123 ms past the second: the timestamp must still be whole seconds
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-26T08:00:00.123Z"), ZoneOffset.UTC);
  private final HmacTimestampAuthStrategy strategy = new HmacTimestampAuthStrategy(clock);

  @Test
  @DisplayName("supports HMAC_TIMESTAMP")
  void supports_hmacTimestamp() {
    assertThat(strategy.supports()).isEqualTo(AuthType.HMAC_TIMESTAMP);
  }

  @Test
  @DisplayName("returns the timestamp header and the signature header, the signature matching the external value")
  void sign_body_returnsTimestampAndSignature() {
    StepVerifier.create(strategy.authenticate(input(BODY), config(), encryption))
        .assertNext(credentials -> assertThat(credentials).containsExactly(
            new AuthCredential("X-Timestamp", TIMESTAMP, TokenInjectLocation.HEADER),
            new AuthCredential("X-Signature", SIGNATURE_OF_BODY, TokenInjectLocation.HEADER)))
        .verifyComplete();
  }

  @Test
  @DisplayName("a request without a body signs the timestamp, the separator and nothing else")
  void sign_noBody_signsTimestampAndSeparator() {
    List<AuthCredential> credentials = strategy.authenticate(input(null), config(), encryption).block();

    assertThat(credentials.get(1).value()).isEqualTo(SIGNATURE_OF_EMPTY);
  }

  @Test
  @DisplayName("header names come from the configuration")
  void sign_customHeaderNames_used() {
    OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
        .clientSecret(encryption.encryptString("golden-secret"))
        .tokenKeyName("X-Partner-Sig")
        .authSettings(Map.of("timestampHeader", "X-Partner-Time"))
        .build();

    List<AuthCredential> credentials = strategy.authenticate(input(BODY), config, encryption).block();

    assertThat(credentials).extracting(AuthCredential::keyName).containsExactly("X-Partner-Time", "X-Partner-Sig");
  }

  @Test
  @DisplayName("a missing secret fails with a configuration error instead of signing with an empty key")
  void sign_missingSecret_fails() {
    OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
        .tokenKeyName("X-Signature")
        .authSettings(Map.of("timestampHeader", "X-Timestamp"))
        .build();

    StepVerifier.create(strategy.authenticate(input(BODY), config, encryption))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  @DisplayName("a missing timestamp header name fails with a configuration error")
  void sign_missingTimestampHeader_fails() {
    OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
        .clientSecret(encryption.encryptString("golden-secret"))
        .tokenKeyName("X-Signature")
        .build();

    StepVerifier.create(strategy.authenticate(input(BODY), config, encryption))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  private SigningInput input(String body) {
    return new SigningInput("POST", "/v1/pay", Map.of(), Map.of(), body);
  }

  private OutboundAuthConfiguration config() {
    return OutboundAuthConfiguration.builder()
        .clientSecret(encryption.encryptString("golden-secret"))
        .tokenKeyName("X-Signature")
        .authSettings(Map.of("timestampHeader", "X-Timestamp"))
        .build();
  }
}
