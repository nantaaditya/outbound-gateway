package com.nantaaditya.example.service.strategy.auth;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.service.AuthStrategy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class ApiKeyAuthStrategy implements AuthStrategy {

  @Override
  public AuthType supports() {
    return AuthType.API_KEY;
  }

  @Override
  public Mono<AuthCredential> authenticate(
      OutboundAuthConfiguration config, EncryptionHelper encryption, String requestBody) {
    return Mono.fromCallable(() -> {
      String apiKey = encryption.decryptToString(config.getApiKey());
      return new AuthCredential(
          config.getTokenKeyName(),
          apiKey,
          config.getTokenInjectLocation());
    }).subscribeOn(Schedulers.boundedElastic());
  }
}
