package com.nantaaditya.example.service.strategy.auth;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.service.AuthStrategy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class NoneAuthStrategy implements AuthStrategy {

  @Override
  public AuthType supports() {
    return AuthType.NONE;
  }

  @Override
  public Mono<AuthCredential> authenticate(
      OutboundAuthConfiguration config, EncryptionHelper encryption, String requestBody) {
    return Mono.just(AuthCredential.empty());
  }
}
