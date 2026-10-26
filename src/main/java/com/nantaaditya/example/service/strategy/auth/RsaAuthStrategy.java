package com.nantaaditya.example.service.strategy.auth;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.SigningInput;
import com.nantaaditya.example.service.AuthStrategy;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Signs the outbound request body with RSA-SHA256 using a PKCS8 DER private key.
 *
 * <p>The private key is stored AES-256-GCM encrypted in {@code client_secret} as raw DER bytes
 * (not PEM — no header/footer stripping required). On the first call per config ID, the bytes
 * are decrypted and loaded via {@link java.security.spec.PKCS8EncodedKeySpec}; the parsed
 * {@link PrivateKey} is cached in {@link #keyCache} via
 * {@link ConcurrentHashMap#computeIfAbsent computeIfAbsent} so subsequent calls skip the
 * {@link KeyFactory} overhead entirely. Call {@link #evictTokenState(Long)} (triggered by
 * admin upsert-authentication) to drop the cached key and force a fresh load on the next request.
 *
 * <p>If loading or signing fails a {@link RuntimeException} is thrown, which surfaces as a
 * gateway 502 to the caller. A bad key is a configuration error that should never silently
 * pass through.
 *
 * <p>Like HMAC, an empty string is signed when the request body is {@code null}, and the
 * resulting signature bytes are Base64-encoded before injection.
 */
@Component
public class RsaAuthStrategy implements AuthStrategy {

  private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

  private final ConcurrentHashMap<Long, PrivateKey> keyCache = new ConcurrentHashMap<>();

  @Override
  public AuthType supports() {
    return AuthType.RSA;
  }

  @Override
  public void evictTokenState(Long configId) {
    keyCache.remove(configId);
  }

  @Override
  public Mono<List<AuthCredential>> authenticate(
      SigningInput input, OutboundAuthConfiguration authConfiguration, EncryptionHelper encryption) {
    return Mono.fromCallable(() -> {
      PrivateKey privateKey = keyCache.computeIfAbsent(
          authConfiguration.getId(),
          id -> {
            byte[] privateKeyBytes = encryption.decrypt(authConfiguration.getClientSecret());
            return loadPrivateKey(privateKeyBytes);
          }
      );
      String signature = sign(privateKey, input.body());
      return List.of(new AuthCredential(
          authConfiguration.getTokenKeyName(),
          signature,
          authConfiguration.getTokenInjectLocation()));
    }).subscribeOn(Schedulers.boundedElastic());
  }

  private PrivateKey loadPrivateKey(byte[] privateKeyBytes) {
    try {
      KeyFactory kf = KeyFactory.getInstance("RSA");
      return kf.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to load RSA private key", e);
    }
  }

  private String sign(PrivateKey key, String data) {
    try {
      Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
      sig.initSign(key);
      sig.update(data.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(sig.sign());
    } catch (Exception e) {
      throw new IllegalArgumentException("RSA signing failed", e);
    }
  }
}
