package com.nantaaditya.example.service.strategy.auth;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.service.AuthStrategy;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Fetches and caches OAuth 2.0 client-credentials tokens with thundering-herd protection.
 *
 * <h3>Two-layer token cache</h3>
 * <ol>
 *   <li><strong>Live state ({@code tokenStates})</strong> — a {@link ConcurrentHashMap} keyed by
 *       config ID, each value an {@link java.util.concurrent.atomic.AtomicReference} holding the
 *       current {@code (token, expiryTime)} pair. {@code AtomicReference} makes the pair visible
 *       atomically — no partial reads of a stale token with a new expiry (or vice versa).
 *   <li><strong>In-flight deduplicator ({@code tokenFetchCache})</strong> — when the live state
 *       is expired or absent, a single {@code Mono<String>} is stored here via
 *       {@link ConcurrentHashMap#computeIfAbsent}. {@link reactor.core.publisher.Mono#cache()} on
 *       that {@code Mono} means all concurrent callers subscribe to the same in-flight HTTP request
 *       rather than each firing their own — thundering-herd prevention. The entry is removed in
 *       {@code doFinally} so the next expiry triggers a fresh fetch.
 * </ol>
 *
 * <h3>Auto-refresh and DB persistence</h3>
 * <p>When {@code auto_refresh} is enabled, the resolved expiry (parsed from
 * {@code token_response_expiry_path} as seconds-from-now) is written back to
 * {@code outbound_auth_configurations} via {@code authRepository.updateToken}. This survives
 * application restarts — on startup the live state is seeded from the DB row's
 * {@code current_token} and {@code token_expiry_time}. A DB write failure is logged and
 * swallowed; the in-memory token is still used so a persistence blip does not break outbound calls.
 *
 * <h3>Token field extraction</h3>
 * <p>Both {@code token_response_path} and {@code token_response_expiry_path} support either a
 * plain JSON key (simple map lookup) or a JSONPath expression starting with {@code $.} for
 * nested response structures.
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class OAuthClientCredentialsAuthStrategy implements AuthStrategy {

  private record TokenState(String token, LocalDateTime expiryTime) {
    boolean isExpired() {
      return expiryTime == null || LocalDateTime.now().isAfter(expiryTime);
    }
  }

  private final WebClient.Builder webClientBuilder;
  private final OutboundAuthConfigurationRepository authRepository;

  // Live token state per config id — AtomicReference makes (token, expiry) pair visible atomically.
  private final ConcurrentHashMap<Long, AtomicReference<TokenState>> tokenStates = new ConcurrentHashMap<>();
  // In-flight fetch deduplicator — Mono.cache() makes concurrent callers share one HTTP request.
  private final ConcurrentHashMap<Long, Mono<String>> tokenFetchCache = new ConcurrentHashMap<>();

  private WebClient oauthWebClient;

  @PostConstruct
  void init() {
    this.oauthWebClient = webClientBuilder.build();
  }

  @Override
  public AuthType supports() {
    return AuthType.OAUTH;
  }

  @Override
  public Mono<AuthCredential> authenticate(
      OutboundAuthConfiguration config, EncryptionHelper encryption, String requestBody) {
    return resolveToken(config, encryption)
        .map(token -> buildCredential(config, token));
  }

  @Override
  public void evictTokenState(Long configId) {
    tokenStates.remove(configId);
    tokenFetchCache.remove(configId);
  }

  private Mono<String> resolveToken(OutboundAuthConfiguration config, EncryptionHelper encryption) {
    AtomicReference<TokenState> stateRef = tokenStates.computeIfAbsent(
        config.getId(),
        id -> new AtomicReference<>(new TokenState(config.getCurrentToken(), config.getTokenExpiryTime())));
    TokenState state = stateRef.get();
    if (state != null && state.token() != null && !state.isExpired()) {
      return Mono.just(state.token());
    }
    return tokenFetchCache.computeIfAbsent(config.getId(), id ->
        fetchToken(config, encryption, stateRef)
            .cache()
            .doFinally(signal -> tokenFetchCache.remove(config.getId()))
    );
  }

  private Mono<String> fetchToken(
      OutboundAuthConfiguration config, EncryptionHelper encryption, AtomicReference<TokenState> stateRef) {
    return Mono.defer(() -> {
      String clientId = encryption.decryptToString(config.getClientId());
      String clientSecret = encryption.decryptToString(config.getClientSecret());

      return oauthWebClient
          .method(HttpMethod.valueOf(config.getTokenMethod()))
          .uri(config.getTokenUrl())
          .body(BodyInserters.fromFormData("grant_type", "client_credentials")
              .with("client_id", clientId)
              .with("client_secret", clientSecret))
          .retrieve()
          .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
          .flatMap(response -> {
            String token = extractPath(response, config.getTokenResponsePath());
            if (token == null || token.isBlank()) {
              return Mono.error(new RuntimeException(
                  "OAuth token response missing field: " + config.getTokenResponsePath()));
            }
            if (!config.isAutoRefresh()) {
              return Mono.just(token);
            }
            LocalDateTime expiry = null;
            String expiryPath = config.getTokenResponseExpiryPath();
            if (expiryPath == null || expiryPath.isBlank()) {
              log.warn(AppLogMessage.message(
                  "OAuth expiry path not configured for config {} — token will be refetched on every call. " +
                  "Set token_response_expiry_path to enable token caching.", config.getId()));
            } else {
              String expiryStr = extractPath(response, expiryPath);
              if (expiryStr != null) {
                try {
                  expiry = LocalDateTime.now().plusSeconds(Long.parseLong(expiryStr));
                } catch (NumberFormatException ignored) {
                  log.warn(AppLogMessage.message("Cannot parse token expiry: {}", expiryStr));
                }
              }
            }
            stateRef.set(new TokenState(token, expiry));
            return authRepository.updateToken(config.getId(), config.getOutboundSystemId(), token, expiry)
                .thenReturn(token)
                .onErrorResume(e -> {
                  log.warn(AppLogMessage.message("Failed to persist OAuth token for config {}: {}", config.getId(), e.getMessage()).error(e));
                  return Mono.just(token);
                });
          });
    }).subscribeOn(Schedulers.boundedElastic());
  }

  private String extractPath(Map<String, Object> response, String path) {
    if (path == null || path.isBlank() || response == null) return null;
    if (path.startsWith("$.")) {
      try {
        Object value = JsonPath.read(response, path);
        return value != null ? value.toString() : null;
      } catch (PathNotFoundException e) {
        log.warn(AppLogMessage.message("JSONPath '{}' not found in token response", path));
        return null;
      } catch (Exception e) {
        log.warn(AppLogMessage.message("JSONPath extraction failed for path '{}': {}", path, e.getMessage()).error(e));
        return null;
      }
    }
    Object value = response.get(path);
    return value != null ? value.toString() : null;
  }

  private AuthCredential buildCredential(OutboundAuthConfiguration config, String token) {
    String prefix = config.getTokenPrefix();
    String value = (prefix != null && !prefix.isBlank()) ? prefix + " " + token : token;
    return new AuthCredential(
        config.getTokenKeyName(),
        value,
        config.getTokenInjectLocation());
  }
}
