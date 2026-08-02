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
 * Injects a static API key into the outbound request.
 *
 * <p>The key is stored AES-256-GCM encrypted in {@code outbound_auth_configurations.api_key}.
 * It is decrypted on every call (no in-memory caching) via {@link com.nantaaditya.example.helper.EncryptionHelper}.
 * Decryption runs on {@code Schedulers.boundedElastic()} to avoid blocking the event loop.
 *
 * <p>Inject location and header/query-param name are fully configurable via
 * {@code token_key_name} and {@code token_inject_location} in the authentication config row.
 */
@Component
public class ApiKeyAuthStrategy implements AuthStrategy {

  @Override
  public AuthType supports() {
    return AuthType.API_KEY;
  }

  @Override
  public Mono<AuthCredential> authenticate(
      OutboundAuthConfiguration authConfiguration, EncryptionHelper encryption, String requestBody) {
    return Mono.fromCallable(() -> {
      String apiKey = encryption.decryptToString(authConfiguration.getApiKey());
      return new AuthCredential(
          authConfiguration.getTokenKeyName(),
          apiKey,
          authConfiguration.getTokenInjectLocation());
    }).subscribeOn(Schedulers.boundedElastic());
  }
}
