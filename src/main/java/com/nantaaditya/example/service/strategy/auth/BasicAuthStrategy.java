package com.nantaaditya.example.service.strategy.auth;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.service.AuthStrategy;
import java.util.Base64;
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
  public Mono<AuthCredential> authenticate(
      OutboundAuthConfiguration config, EncryptionHelper encryption, String requestBody) {
    return Mono.fromCallable(() -> {
      String clientId = encryption.decryptToString(config.getClientId());
      String clientSecret = encryption.decryptToString(config.getClientSecret());
      String encoded = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
      return new AuthCredential("Authorization", "Basic " + encoded, TokenInjectLocation.HEADER);
    }).subscribeOn(Schedulers.boundedElastic());
  }
}
