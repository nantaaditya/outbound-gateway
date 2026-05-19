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
