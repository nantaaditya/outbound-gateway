package com.nantaaditya.example.service.strategy.auth;

import com.nantaaditya.example.crypto.EncryptionHelper;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.SigningInput;
import com.nantaaditya.example.service.AuthStrategy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Produces an HTTP Basic Authentication header from encrypted credentials.
 *
 * <p>Both {@code client_id} and {@code client_secret} are AES-256-GCM encrypted at rest.
 * On each call they are decrypted, concatenated as {@code clientId:clientSecret}, and
 * Base64-encoded into the standard {@code Authorization: Basic <encoded>} header.
 *
 * <p>The inject location is always {@link com.nantaaditya.example.model.constant.TokenInjectLocation#HEADER}
 * — Basic authentication is defined only as a header mechanism.
 */
@Component
public class BasicAuthStrategy implements AuthStrategy {

  @Override
  public AuthType supports() {
    return AuthType.BASIC;
  }

  @Override
  public Mono<List<AuthCredential>> authenticate(
      SigningInput input, OutboundAuthConfiguration authConfiguration, EncryptionHelper encryption) {
    return Mono.fromCallable(() -> {
      String clientId = encryption.decryptToString(authConfiguration.getClientId());
      String clientSecret = encryption.decryptToString(authConfiguration.getClientSecret());
      String encoded = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
      return List.of(new AuthCredential("Authorization", "Basic " + encoded, TokenInjectLocation.HEADER));
    }).subscribeOn(Schedulers.boundedElastic());
  }
}
