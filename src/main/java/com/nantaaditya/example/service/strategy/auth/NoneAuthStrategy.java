package com.nantaaditya.example.service.strategy.auth;

import com.nantaaditya.example.crypto.EncryptionHelper;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.SigningInput;
import com.nantaaditya.example.service.AuthStrategy;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class NoneAuthStrategy implements AuthStrategy {

  @Override
  public AuthType supports() {
    return AuthType.NONE;
  }

  @Override
  public Mono<List<AuthCredential>> authenticate(
      SigningInput input, OutboundAuthConfiguration config, EncryptionHelper encryption) {
    return Mono.just(List.of());
  }
}
