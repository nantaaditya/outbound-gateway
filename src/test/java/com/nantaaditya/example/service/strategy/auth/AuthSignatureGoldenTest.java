package com.nantaaditya.example.service.strategy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Known-answer tests: the exact signature bytes of the existing HMAC and RSA strategies for fixed inputs.
 *
 * <p>The expected values were computed outside this code base (Python {@code hmac} for HMAC, {@code openssl dgst
 * -sha256 -sign} for RSA), so they do not merely agree with the implementation. Partners verify these bytes; a
 * refactor of the auth/signing pipeline that changes one of them breaks every partner using the scheme.
 *
 * <p>{@link #RSA_PKCS8_DER_BASE64} is a throw-away 1024-bit key generated for this test only. It protects nothing.
 */
class AuthSignatureGoldenTest {

  private static final String BODY = "{\"amount\":100,\"txId\":\"tx-1\"}";
  private static final String HMAC_SECRET = "golden-secret";
  private static final String HMAC_OF_BODY = "lb/tJGWpXDMIInjuqKQYTqTJUJOrfo6ACohScMcIums=";
  private static final String HMAC_OF_EMPTY = "2PEp+OE3BRxw+wPtjlUID6GjdGUlVUbyURU5pvPYVeA=";

  private static final String RSA_PKCS8_DER_BASE64 =
      "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAJuhpeZqZ/+7rztGwBeYwq/92Tq4/1wwdLN2sR0X7bVrKvWVTFgbnmAe"
          + "KPizgwJ+nddOyCWP86Hf4vVeX2s/P9dawE9D92BGSYXyN8E8i5LQ+zgtOaIR/5DhHajuLoJ1pMhN1LZ1bfoa77AOAXAj0jCz/FQtIN3U"
          + "2+Ae6z3QZMnxAgMBAAECgYBVstWf48gQ0lDT20m7tKrALpCH/YbgRO5+REWTN7H1zGWDhgrcGZlEZFc7W94waXiRLxiXVoVaBeb8JlN0"
          + "wwdruu7aX6a5+S+3LXMncz+5aDDQagNoAFgoDFpmqDq1fG2QprifjR+IGS+9j+JR56diTd3DJRsYryJnfSHl2Dbm4QJBAMzR2580zVJ1"
          + "XFGEXqVLogXZezTwRYQ3Wq5f9ZINBI38igy96SCKlYpvMXPNP9U/SR8/RDXpyNyZdfSVSRs9FKcCQQDChUGLNvUEcc/efDBvXDnU4Icm"
          + "kU50BDGgTucndPzN7X/FMcVJ/6yTSKlH+UTzMmpaV5X9Pj+EPXy52Nt+3kenAkBNpZ053N2dzgoiiSkMRwD0cL9JkXD5L7b79jr1lGPO"
          + "pBd9vx07De6lHYRE32G8qRJCe0yTl75jCvZxs4qUffJvAkBraB8P2rGh2aIYzGQGKVt5ctzNa/Bw2Rv503eJbuxkXJ5vaRLx0C4fSUDH"
          + "kBkFXDIjXjdglO2HfFgRWm+gduYDAkEAjY0NJLFKhtvI7MJSol6iGBtlTJOBSlmj/dkuvG5OuWiJ7Ppgw31uaTyWPbp5pXqyONE7R5R0"
          + "UfvlgNFjh0XOHA==";
  private static final String RSA_OF_BODY =
      "e/ZD6cyg3DtYW+veDNmB/PpXlwFfZ+usGMQ1zhaHMvfoZuyzlamX+75zXrkgoxcs/YYLGOSkp6kLwY5WfC/ip1wlU3MsDMf8MqmY9ar4Iu/d"
          + "j0n7rO0HZAcTOehT5zLD4EIgxxLWhHRb8pZrlcuNU55MotavFRM1UuDU18VfWMU=";
  private static final String RSA_OF_EMPTY =
      "WeUm7axQMKR4qaWYs78BBUUrPqpQC7J26Pg+RFng7eYIs1dfN35uMvigZS7ySCI8LwDXB7k1Zq0KA8U4kb3mVgebAK4uPQwNbQtGrZO0uKH+"
          + "c2Z65B/Exri4h2upZPtZ19UziFvNr2ehcuTwnZZcsXgC5QBlDcvnxIvG1FrVzKQ=";

  private final EncryptionHelper encryption = new EncryptionHelper(Base64.getEncoder().encodeToString(new byte[32]));

  @Test
  @DisplayName("HMAC: the signature of a fixed body and secret is exactly the externally computed value")
  void hmac_body_matchesKnownAnswer() {
    var credential = new HmacSha256AuthStrategy().authenticate(hmacConfig(), encryption, BODY).block();

    assertThat(credential.value()).isEqualTo(HMAC_OF_BODY);
    assertThat(credential.keyName()).isEqualTo("X-Signature");
    assertThat(credential.location()).isEqualTo(TokenInjectLocation.HEADER);
  }

  @Test
  @DisplayName("HMAC: a null body signs the empty string")
  void hmac_nullBody_matchesKnownAnswerOfEmptyString() {
    var credential = new HmacSha256AuthStrategy().authenticate(hmacConfig(), encryption, null).block();

    assertThat(credential.value()).isEqualTo(HMAC_OF_EMPTY);
  }

  @Test
  @DisplayName("RSA: the PKCS#1 v1.5 SHA-256 signature of a fixed body is exactly the openssl value")
  void rsa_body_matchesKnownAnswer() {
    var credential = new RsaAuthStrategy().authenticate(rsaConfig(), encryption, BODY).block();

    assertThat(credential.value()).isEqualTo(RSA_OF_BODY);
    assertThat(credential.keyName()).isEqualTo("X-Signature");
    assertThat(credential.location()).isEqualTo(TokenInjectLocation.HEADER);
  }

  @Test
  @DisplayName("RSA: a null body signs the empty string")
  void rsa_nullBody_matchesKnownAnswerOfEmptyString() {
    var credential = new RsaAuthStrategy().authenticate(rsaConfig(), encryption, null).block();

    assertThat(credential.value()).isEqualTo(RSA_OF_EMPTY);
  }

  private OutboundAuthConfiguration hmacConfig() {
    return OutboundAuthConfiguration.builder()
        .id(1L)
        .clientSecret(encryption.encryptString(HMAC_SECRET))
        .tokenKeyName("X-Signature")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build();
  }

  private OutboundAuthConfiguration rsaConfig() {
    return OutboundAuthConfiguration.builder()
        .id(2L)
        .clientSecret(encryption.encrypt(Base64.getDecoder().decode(RSA_PKCS8_DER_BASE64)))
        .tokenKeyName("X-Signature")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build();
  }
}
