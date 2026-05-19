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
