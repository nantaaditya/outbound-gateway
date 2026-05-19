package com.nantaaditya.example.service.strategy.auth;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.service.AuthStrategy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
      String body = requestBody != null ? requestBody : "";
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
