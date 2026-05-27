package com.nantaaditya.example.service.strategy.auth;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.service.AuthStrategy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Signs the outbound request body with HMAC-SHA256 and injects the signature.
 *
 * <p>The signing secret is stored AES-256-GCM encrypted in {@code client_secret}.
 * The signature is computed over the raw request body string on every call — if the body is
 * {@code null} (e.g. GET requests) an empty string is signed instead so the HMAC is always
 * a valid, verifiable value on the upstream side.
 *
 * <p>The resulting HMAC bytes are Base64-encoded before injection. Inject location and
 * header name follow the same {@code token_key_name} / {@code token_inject_location} pattern.
 */
@Component
public class HmacSha256AuthStrategy implements AuthStrategy {

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  @Override
  public AuthType supports() {
    return AuthType.HMAC;
  }

  @Override
  public Mono<AuthCredential> authenticate(
      OutboundAuthConfiguration config, EncryptionHelper encryption, String requestBody) {
    return Mono.fromCallable(() -> {
      String secret = encryption.decryptToString(config.getClientSecret());
      String body = Optional.ofNullable(requestBody).orElse("");
      String signature = computeHmac(secret, body);
      return new AuthCredential(
          config.getTokenKeyName(),
          signature,
          config.getTokenInjectLocation());
    }).subscribeOn(Schedulers.boundedElastic());
  }

  private String computeHmac(String secret, String data) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new RuntimeException("HMAC computation failed", e);
    }
  }
}
