package com.nantaaditya.example.service;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.dto.AuthCredential;
import reactor.core.publisher.Mono;

public interface AuthStrategy {

  AuthType supports();

  Mono<AuthCredential> authenticate(
      OutboundAuthConfiguration config, EncryptionHelper encryption, String requestBody);

  /**
   * Evicts any cached token state for the given auth config ID.
   * Called after auth config is updated so stale tokens are not reused.
   * Strategies that don't cache tokens (e.g. NONE, API_KEY) leave this as a no-op.
   */
  default void evictTokenState(Long configId) {}
}
