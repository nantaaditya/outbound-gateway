package com.nantaaditya.example.service.strategy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class RsaAuthStrategyTest {

  private static KeyPair keyPair;

  private RsaAuthStrategy strategy;
  private EncryptionHelper encryptionHelper;

  @BeforeAll
  static void generateKeyPair() throws Exception {
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
    kpg.initialize(2048);
    keyPair = kpg.generateKeyPair();
  }

  @BeforeEach
  void setUp() {
    encryptionHelper = new EncryptionHelper(Base64.getEncoder().encodeToString(new byte[32]));
    strategy = new RsaAuthStrategy();
  }

  @Test
  @DisplayName("supports returns RSA")
  void supports_returnsRsa() {
    assertThat(strategy.supports()).isEqualTo(AuthType.RSA);
  }

  @Test
  @DisplayName("authenticate with valid key returns verifiable Base64 signature")
  void authenticate_validKey_returnsVerifiableSignature() throws Exception {
    OutboundAuthConfiguration config = configWithKey(1L, keyPair.getPrivate().getEncoded());
    String body = "{\"amount\":500}";

    var cred = strategy.authenticate(config, encryptionHelper, body).block();

    assertThat(cred).isNotNull();
    assertThat(cred.keyName()).isEqualTo("X-Signature");
    assertThat(cred.location()).isEqualTo(TokenInjectLocation.HEADER);

    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(keyPair.getPublic());
    verifier.update(body.getBytes(StandardCharsets.UTF_8));
    assertThat(verifier.verify(Base64.getDecoder().decode(cred.value()))).isTrue();
  }

  @Test
  @DisplayName("authenticate with null body signs empty string — signature is still valid")
  void authenticate_nullBody_signsEmptyString() throws Exception {
    OutboundAuthConfiguration config = configWithKey(2L, keyPair.getPrivate().getEncoded());

    var cred = strategy.authenticate(config, encryptionHelper, null).block();

    assertThat(cred).isNotNull();
    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(keyPair.getPublic());
    verifier.update(new byte[0]);
    assertThat(verifier.verify(Base64.getDecoder().decode(cred.value()))).isTrue();
  }

  @Test
  @DisplayName("second authenticate call reuses cached private key")
  void authenticate_secondCall_reusesCachedKey() {
    OutboundAuthConfiguration config = configWithKey(3L, keyPair.getPrivate().getEncoded());

    var first = strategy.authenticate(config, encryptionHelper, "body").block();
    var second = strategy.authenticate(config, encryptionHelper, "body").block();

    assertThat(first).isNotNull();
    assertThat(second).isNotNull();
    assertThat(first.value()).isEqualTo(second.value());
  }

  @Test
  @DisplayName("evictTokenState removes cached key and next call reloads successfully")
  void evictTokenState_removesCache_nextCallReloads() {
    OutboundAuthConfiguration config = configWithKey(4L, keyPair.getPrivate().getEncoded());

    strategy.authenticate(config, encryptionHelper, "seed").block();
    strategy.evictTokenState(4L);

    var cred = strategy.authenticate(config, encryptionHelper, "after-evict").block();
    assertThat(cred).isNotNull();
  }

  @Test
  @DisplayName("authenticate with invalid key bytes propagates IllegalArgumentException")
  void authenticate_invalidKeyBytes_propagatesError() {
    byte[] badKeyDer = new byte[]{1, 2, 3, 4};
    OutboundAuthConfiguration config = configWithKey(5L, badKeyDer);

    StepVerifier.create(strategy.authenticate(config, encryptionHelper, "body"))
        .expectErrorMatches(e -> e instanceof IllegalArgumentException
            && e.getMessage().contains("RSA private key"))
        .verify();
  }

  private OutboundAuthConfiguration configWithKey(Long id, byte[] privateDer) {
    byte[] encrypted = encryptionHelper.encrypt(privateDer);
    OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
        .clientSecret(encrypted)
        .tokenKeyName("X-Signature")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build();
    config.setId(id);
    return config;
  }
}
