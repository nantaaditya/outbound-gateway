package com.nantaaditya.example.service.strategy.auth;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.service.AuthStrategy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Injects a long-lived static bearer token into the outbound request.
 *
 * <p>The token is stored AES-256-GCM encrypted in {@code outbound_auth_configurations.client_secret}
 * (reusing the secret field — there is no separate token column for static tokens).
 *
 * <p>An optional {@code token_prefix} (e.g. {@code "Bearer"}, {@code "Token"}) is prepended
 * with a space separator. If the prefix is blank the raw decrypted value is used as-is, which
 * covers upstreams that expect the token without a prefix keyword.
 *
 * <p>Inject location and header name are configurable via {@code token_key_name} and
 * {@code token_inject_location}, matching the same convention as the other strategies.
 */
@Component
public class BearerStaticAuthStrategy implements AuthStrategy {

  @Override
  public AuthType supports() {
    return AuthType.BEARER_STATIC;
  }

  @Override
  public Mono<AuthCredential> authenticate(
      OutboundAuthConfiguration config, EncryptionHelper encryption, String requestBody) {
    return Mono.fromCallable(() -> {
      String token = encryption.decryptToString(config.getClientSecret());
      String prefix = config.getTokenPrefix();
      String value = (prefix != null && !prefix.isBlank()) ? prefix + " " + token : token;
      return new AuthCredential(
          config.getTokenKeyName(),
          value,
          config.getTokenInjectLocation());
    }).subscribeOn(Schedulers.boundedElastic());
  }
}
