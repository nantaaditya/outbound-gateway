package com.nantaaditya.example.service.strategy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.SigningInput;
import com.nantaaditya.example.service.AuthStrategy;
import java.util.Map;
import reactor.core.publisher.Mono;

/** Test helpers for strategies that look only at the body and yield exactly one credential. */
public final class AuthTestSupport {

  private AuthTestSupport() {
  }

  /** A request carrying only a body; the other parts are irrelevant to body-only strategies. */
  public static SigningInput bodyOnly(String body) {
    return new SigningInput("POST", "/", Map.of(), Map.of(), body);
  }

  /** Authenticates {@code body} and unwraps the single credential, failing if there is not exactly one. */
  public static Mono<AuthCredential> single(
      AuthStrategy strategy, OutboundAuthConfiguration config, EncryptionHelper encryption, String body) {
    return strategy.authenticate(bodyOnly(body), config, encryption).map(credentials -> {
      assertThat(credentials).hasSize(1);
      return credentials.get(0);
    });
  }
}
