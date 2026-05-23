package com.nantaaditya.example.service.strategy.auth;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.service.AuthStrategy;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.nio.charset.StandardCharsets;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Signs the outbound request body with RSA-SHA256 using a PKCS8 DER private key.
 *
 * <p>The private key is stored AES-256-GCM encrypted in {@code client_secret} as raw DER bytes
 * (not PEM — no header/footer stripping required). On every call the bytes are decrypted,
 * loaded via {@link java.security.spec.PKCS8EncodedKeySpec}, and used to produce a
 * {@code SHA256withRSA} signature over the request body.
 *
 * <p>The key is not cached — if loading or signing fails a {@link RuntimeException} is thrown,
 * which surfaces as a gateway 502 to the caller. This is intentional: a bad key is a
 * configuration error that should never silently pass through.
 *
 * <p>Like HMAC, an empty string is signed when the request body is {@code null}, and the
 * resulting signature bytes are Base64-encoded before injection.
 */
@Component
public class RsaAuthStrategy implements AuthStrategy {

  private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

  @Override
  public AuthType supports() {
    return AuthType.RSA;
  }

  @Override
  public Mono<AuthCredential> authenticate(
      OutboundAuthConfiguration config, EncryptionHelper encryption, String requestBody) {
    return Mono.fromCallable(() -> {
      byte[] privateKeyBytes = encryption.decrypt(config.getClientSecret());
      PrivateKey privateKey = loadPrivateKey(privateKeyBytes);
      String body = requestBody != null ? requestBody : "";
      String signature = sign(privateKey, body);
      return new AuthCredential(
          config.getTokenKeyName(),
          signature,
          config.getTokenInjectLocation());
    }).subscribeOn(Schedulers.boundedElastic());
  }

  private PrivateKey loadPrivateKey(byte[] der) {
    try {
      KeyFactory kf = KeyFactory.getInstance("RSA");
      return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (Exception e) {
      throw new RuntimeException("Failed to load RSA private key", e);
    }
  }

  private String sign(PrivateKey key, String data) {
    try {
      Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
      sig.initSign(key);
      sig.update(data.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(sig.sign());
    } catch (Exception e) {
      throw new RuntimeException("RSA signing failed", e);
    }
  }
}
