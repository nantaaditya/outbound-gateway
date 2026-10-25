package com.nantaaditya.example.service;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.SigningInput;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * An {@link AuthStrategy} that signs the prepared request instead of only the body, and may need more than one
 * header (for example a timestamp next to the signature). The executor calls {@link #sign} for these; the
 * body-only {@link #authenticate} is not meaningful for them.
 */
public interface RequestSigner extends AuthStrategy {

  Mono<List<AuthCredential>> sign(SigningInput input, OutboundAuthConfiguration config, EncryptionHelper encryption);

  @Override
  default Mono<AuthCredential> authenticate(
      OutboundAuthConfiguration config, EncryptionHelper encryption, String requestBody) {
    return Mono.error(new UnsupportedOperationException(
        "request signers need the whole request; the executor calls sign(...)"));
  }
}
