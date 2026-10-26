package com.nantaaditya.example.service;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.dto.SigningInput;
import java.util.List;
import reactor.core.publisher.Mono;

public interface AuthStrategy {

  AuthType supports();

  /**
   * Produces the credentials to attach to the outbound request: none (NONE), one (API key, token, body signature) or
   * several (a timestamp next to a signature). {@code input} is the request as it will go on the wire; strategies that
   * only need the body read {@link SigningInput#body()} and ignore the rest.
   */
  Mono<List<AuthCredential>> authenticate(
      SigningInput input, OutboundAuthConfiguration config, EncryptionHelper encryption);

  /**
   * Evicts any cached token state for the given authentication config ID.
   * Called after authentication config is updated so stale tokens are not reused.
   * Strategies that don't cache tokens (e.g. NONE, API_KEY) leave this as a no-op.
   */
  default void evictTokenState(Long configId) {}
}
