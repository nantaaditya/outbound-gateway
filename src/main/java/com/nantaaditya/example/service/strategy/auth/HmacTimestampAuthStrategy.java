package com.nantaaditya.example.service.strategy.auth;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.SigningInput;
import com.nantaaditya.example.service.RequestSigner;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Signs {@code timestamp + "." + body} with HMAC-SHA256 and sends two headers: the timestamp and the signature.
 *
 * <ul>
 *   <li>timestamp: ISO-8601 UTC instant in whole seconds, e.g. {@code 2026-10-26T08:00:00Z}</li>
 *   <li>signature: Base64 of the HMAC bytes; the secret is the AES-encrypted {@code client_secret}</li>
 *   <li>signature header name: {@code token_key_name}; timestamp header name: {@code auth_settings.timestampHeader}</li>
 *   <li>both are always sent as headers ({@code token_inject_location} is not used)</li>
 * </ul>
 *
 * <p>The timestamp is what makes a captured request useless later: the receiving side must reject a timestamp that is
 * too old. Only the body is signed, not method, path or query; unlike {@link HmacSha256AuthStrategy} (body only, no
 * timestamp, so a captured request can be replayed indefinitely) the signature cannot be reused with another time.
 */
@Component
@RequiredArgsConstructor
public class HmacTimestampAuthStrategy implements RequestSigner {

  static final String TIMESTAMP_HEADER_SETTING = "timestampHeader";
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String SEPARATOR = ".";

  private final Clock clock;

  @Override
  public AuthType supports() {
    return AuthType.HMAC_TIMESTAMP;
  }

  @Override
  public Mono<List<AuthCredential>> sign(
      SigningInput input, OutboundAuthConfiguration config, EncryptionHelper encryption) {
    return Mono.fromCallable(() -> {
      String timestampHeader = requireSetting(config);
      String signatureHeader = require(config.getTokenKeyName(), "token_key_name (signature header)");
      if (config.getClientSecret() == null || config.getClientSecret().length == 0) {
        throw new IllegalArgumentException("HMAC_TIMESTAMP requires client_secret");
      }
      String secret = encryption.decryptToString(config.getClientSecret());
      String timestamp = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString();
      String signature = hmacBase64(secret, timestamp + SEPARATOR + input.body());
      return List.of(
          new AuthCredential(timestampHeader, timestamp, TokenInjectLocation.HEADER),
          new AuthCredential(signatureHeader, signature, TokenInjectLocation.HEADER));
    }).subscribeOn(Schedulers.boundedElastic());
  }

  private static String requireSetting(OutboundAuthConfiguration config) {
    Object value = config.getAuthSettings() == null ? null : config.getAuthSettings().get(TIMESTAMP_HEADER_SETTING);
    return require(value == null ? null : value.toString(), "auth_settings." + TIMESTAMP_HEADER_SETTING);
  }

  private static String require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("HMAC_TIMESTAMP requires " + name);
    }
    return value;
  }

  private static String hmacBase64(String secret, String data) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalArgumentException("HMAC computation failed", e);
    }
  }
}
