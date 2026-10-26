package com.nantaaditya.example.service.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.dto.AuthCredential;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.service.strategy.auth.ApiKeyAuthStrategy;
import com.nantaaditya.example.service.strategy.auth.AuthTestSupport;
import com.nantaaditya.example.service.strategy.auth.BasicAuthStrategy;
import com.nantaaditya.example.service.strategy.auth.BearerStaticAuthStrategy;
import com.nantaaditya.example.service.strategy.auth.HmacSha256AuthStrategy;
import com.nantaaditya.example.service.strategy.auth.NoneAuthStrategy;
import com.nantaaditya.example.service.strategy.auth.OAuthClientCredentialsAuthStrategy;
import com.nantaaditya.example.service.strategy.auth.RsaAuthStrategy;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuthStrategyTest {

  private EncryptionHelper encryptionHelper;

  @BeforeEach
  void setUp() {
    String key = Base64.getEncoder().encodeToString(new byte[32]);
    encryptionHelper = new EncryptionHelper(key);
  }

  @Nested
  class NoneAuthStrategyTest {

    @Test
    @DisplayName("supports NONE authentication type")
    void supports_returnsNone() {
      assertThat(new NoneAuthStrategy().supports()).isEqualTo(AuthType.NONE);
    }

    @Test
    @DisplayName("authenticate returns no credentials")
    void authenticate_returnsEmpty() {
      OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
          .authType(AuthType.NONE)
          .build();

      StepVerifier.create(new NoneAuthStrategy().authenticate(AuthTestSupport.bodyOnly(null), config, encryptionHelper))
          .assertNext(credentials -> assertThat(credentials).isEmpty())
          .verifyComplete();
    }
  }

  @Nested
  class ApiKeyAuthStrategyTest {

    @Test
    @DisplayName("supports API_KEY authentication type")
    void supports_returnsApiKey() {
      assertThat(new ApiKeyAuthStrategy().supports()).isEqualTo(AuthType.API_KEY);
    }

    @Test
    @DisplayName("authenticate injects api key into header")
    void authenticate_injectsApiKeyInHeader() {
      byte[] encryptedKey = encryptionHelper.encryptString("my-api-key-value");
      OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
          .authType(AuthType.API_KEY)
          .apiKey(encryptedKey)
          .tokenKeyName("X-API-Key")
          .tokenInjectLocation(TokenInjectLocation.HEADER)
          .build();

      StepVerifier.create(AuthTestSupport.single(new ApiKeyAuthStrategy(), config, encryptionHelper, null))
          .assertNext(cred -> {
            assertThat(cred.keyName()).isEqualTo("X-API-Key");
            assertThat(cred.value()).isEqualTo("my-api-key-value");
            assertThat(cred.location()).isEqualTo(TokenInjectLocation.HEADER);
          })
          .verifyComplete();
    }
  }

  @Nested
  class BasicAuthStrategyTest {

    @Test
    @DisplayName("supports BASIC authentication type")
    void supports_returnsBasic() {
      assertThat(new BasicAuthStrategy().supports()).isEqualTo(AuthType.BASIC);
    }

    @Test
    @DisplayName("authenticate builds Basic Authorization header")
    void authenticate_buildsBasicAuthHeader() {
      byte[] encryptedClientId = encryptionHelper.encryptString("user");
      byte[] encryptedClientSecret = encryptionHelper.encryptString("pass");
      OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
          .authType(AuthType.BASIC)
          .clientId(encryptedClientId)
          .clientSecret(encryptedClientSecret)
          .build();

      StepVerifier.create(AuthTestSupport.single(new BasicAuthStrategy(), config, encryptionHelper, null))
          .assertNext(cred -> {
            assertThat(cred.keyName()).isEqualTo("Authorization");
            assertThat(cred.value()).startsWith("Basic ");
            String decoded = new String(Base64.getDecoder().decode(cred.value().substring(6)));
            assertThat(decoded).isEqualTo("user:pass");
            assertThat(cred.location()).isEqualTo(TokenInjectLocation.HEADER);
          })
          .verifyComplete();
    }
  }

  @Nested
  class BearerStaticAuthStrategyTest {

    @Test
    @DisplayName("supports BEARER_STATIC authentication type")
    void supports_returnsBearerStatic() {
      assertThat(new BearerStaticAuthStrategy().supports()).isEqualTo(AuthType.BEARER_STATIC);
    }

    @Test
    @DisplayName("authenticate injects static token with prefix")
    void authenticate_injectsStaticBearerToken() {
      byte[] encryptedSecret = encryptionHelper.encryptString("static-token-abc");
      OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
          .authType(AuthType.BEARER_STATIC)
          .clientSecret(encryptedSecret)
          .tokenPrefix("Bearer")
          .tokenInjectLocation(TokenInjectLocation.HEADER)
          .tokenKeyName("Authorization")
          .build();

      StepVerifier.create(AuthTestSupport.single(new BearerStaticAuthStrategy(), config, encryptionHelper, null))
          .assertNext(cred -> {
            assertThat(cred.keyName()).isEqualTo("Authorization");
            assertThat(cred.value()).isEqualTo("Bearer static-token-abc");
            assertThat(cred.location()).isEqualTo(TokenInjectLocation.HEADER);
          })
          .verifyComplete();
    }
  }

  @Nested
  class HmacSha256AuthStrategyTest {

    @Test
    @DisplayName("supports HMAC authentication type")
    void supports_returnsHmac() {
      assertThat(new HmacSha256AuthStrategy().supports()).isEqualTo(AuthType.HMAC);
    }

    @Test
    @DisplayName("authenticate computes HMAC-SHA256 signature of request body")
    void authenticate_computesHmacSignature() {
      byte[] encryptedSecret = encryptionHelper.encryptString("hmac-secret-key");
      OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
          .authType(AuthType.HMAC)
          .clientSecret(encryptedSecret)
          .tokenKeyName("X-Signature")
          .tokenInjectLocation(TokenInjectLocation.HEADER)
          .build();

      StepVerifier.create(
          AuthTestSupport.single(new HmacSha256AuthStrategy(), config, encryptionHelper, "{\"amount\":100}"))
          .assertNext(cred -> {
            assertThat(cred.keyName()).isEqualTo("X-Signature");
            assertThat(cred.value()).isNotBlank();
            assertThat(cred.location()).isEqualTo(TokenInjectLocation.HEADER);
          })
          .verifyComplete();
    }
  }

  @Nested
  class RsaAuthStrategyTest {

    private RsaAuthStrategy strategy;
    private EncryptionHelper rsaEncryption;
    private KeyPair rsaKeyPair;

    @BeforeEach
    void setUp() throws Exception {
      strategy = new RsaAuthStrategy();
      String key = Base64.getEncoder().encodeToString(new byte[32]);
      rsaEncryption = new EncryptionHelper(key);
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      rsaKeyPair = gen.generateKeyPair();
    }

    @Test
    @DisplayName("supports RSA authentication type")
    void supports_returnsRsa() {
      assertThat(strategy.supports()).isEqualTo(AuthType.RSA);
    }

    @Test
    @DisplayName("authenticate signs body and caches parsed PrivateKey")
    @SuppressWarnings("unchecked")
    void authenticate_signsBodyAndCachesKey() {
      byte[] encryptedKey = rsaEncryption.encrypt(rsaKeyPair.getPrivate().getEncoded());
      OutboundAuthConfiguration config = OutboundAuthConfiguration.builder()
          .id(1L)
          .authType(AuthType.RSA)
          .clientSecret(encryptedKey)
          .tokenKeyName("X-Signature")
          .tokenInjectLocation(TokenInjectLocation.HEADER)
          .build();

      StepVerifier.create(AuthTestSupport.single(strategy, config, rsaEncryption, "{\"amount\":100}"))
          .assertNext(cred -> {
            assertThat(cred.keyName()).isEqualTo("X-Signature");
            assertThat(cred.value()).isNotBlank();
            assertThat(cred.location()).isEqualTo(TokenInjectLocation.HEADER);
          })
          .verifyComplete();

      ConcurrentHashMap<Long, PrivateKey> keyCache =
          (ConcurrentHashMap<Long, PrivateKey>) ReflectionTestUtils.getField(strategy, "keyCache");
      assertThat(keyCache).containsKey(1L);
    }

    @Test
    @DisplayName("evictTokenState removes key from cache")
    @SuppressWarnings("unchecked")
    void evictTokenState_removesKeyFromCache() {
      ConcurrentHashMap<Long, PrivateKey> keyCache =
          (ConcurrentHashMap<Long, PrivateKey>) ReflectionTestUtils.getField(strategy, "keyCache");
      keyCache.put(42L, rsaKeyPair.getPrivate());

      strategy.evictTokenState(42L);

      assertThat(keyCache).doesNotContainKey(42L);
    }
  }

  @Nested
  class OAuthEvictTokenStateTest {

    private OAuthClientCredentialsAuthStrategy strategy;

    @BeforeEach
    void setUp() {
      WebClient.Builder builder = Mockito.mock(WebClient.Builder.class);
      OutboundAuthConfigurationRepository repo =
          Mockito.mock(OutboundAuthConfigurationRepository.class);
      strategy = new OAuthClientCredentialsAuthStrategy(builder, repo);
      // init() (PostConstruct) is package-private and not required for eviction tests
    }

    @Test
    @DisplayName("evictTokenState removes entry from both tokenStates and tokenFetchCache")
    @SuppressWarnings("unchecked")
    void evictTokenState_seededEntry_removesFromBothCaches() {
      ConcurrentHashMap<Long, AtomicReference<Object>> tokenStates =
          (ConcurrentHashMap<Long, AtomicReference<Object>>)
              ReflectionTestUtils.getField(strategy, "tokenStates");
      ConcurrentHashMap<Long, Mono<String>> tokenFetchCache =
          (ConcurrentHashMap<Long, Mono<String>>)
              ReflectionTestUtils.getField(strategy, "tokenFetchCache");

      tokenStates.put(42L, new AtomicReference<>());
      tokenFetchCache.put(42L, Mono.just("stale-token").cache());

      strategy.evictTokenState(42L);

      assertThat(tokenStates).doesNotContainKey(42L);
      assertThat(tokenFetchCache).doesNotContainKey(42L);
    }
  }
}
