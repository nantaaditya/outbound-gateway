package com.nantaaditya.example.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import com.nantaaditya.example.model.response.OutboundSystemDetailResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

class OutboundSystemDetailQueryTest extends BaseIntegrationTest {

  @Autowired
  private OutboundSystemDetailQuery query;

  @Autowired
  private OutboundSystemRepository systemRepository;

  @Autowired
  private OutboundResilienceConfigurationRepository resilienceRepository;

  @Autowired
  private OutboundHeaderConfigurationRepository headerRepository;

  @Autowired
  private OutboundAuthConfigurationRepository authRepository;

  @Autowired
  private OutboundHostConfigurationRepository hostConfigRepository;

  @AfterEach
  void cleanup() {
    resilienceRepository.deleteAll().block();
    headerRepository.deleteAll().block();
    authRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
    systemRepository.deleteAll().block();
  }

  @Test
  @DisplayName("count returns total number of outbound systems")
  void count_withTwoSystems_returnsTwo() {
    systemRepository.save(system("count-gw-1", "http://count-1.test")).block();
    systemRepository.save(system("count-gw-2", "http://count-2.test")).block();

    StepVerifier.create(query.count())
        .assertNext(count -> assertThat(count).isEqualTo(2))
        .verifyComplete();
  }

  @Test
  @DisplayName("findAllPaged returns page of systems with their joined config details")
  void findAllPaged_withFullyConfiguredSystem_returnsDetailWithAllConfigs() {
    OutboundSystem saved = systemRepository.save(system("detail-gw", "http://detail.test")).block();
    resilienceRepository.save(resilience(saved.getId())).block();
    authRepository.save(auth(saved.getId())).block();
    hostConfigRepository.save(hostConfig("http://detail.test")).block();

    StepVerifier.create(query.findAllPaged(0, 10).collectList())
        .assertNext(items -> {
          assertThat(items).hasSize(1);
          OutboundSystemDetailResponse detail = items.get(0);
          assertThat(detail.code()).isEqualTo("detail-gw");
          assertThat(detail.resilience()).isNotNull();
          assertThat(detail.resilience().readTimeOutMs()).isEqualTo(5000);
          assertThat(detail.auth()).isNotNull();
          assertThat(detail.auth().authType()).isEqualTo(AuthType.API_KEY);
          assertThat(detail.hostConfig()).isNotNull();
          assertThat(detail.hostConfig().maxConnections()).isEqualTo(50);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findAllPaged respects page and size parameters")
  void findAllPaged_pagination_returnsCorrectPage() {
    for (int i = 1; i <= 3; i++) {
      systemRepository.save(system("page-gw-" + i, "http://page-" + i + ".test")).block();
    }

    StepVerifier.create(query.findAllPaged(0, 2).collectList())
        .assertNext(items -> assertThat(items).hasSize(2))
        .verifyComplete();

    StepVerifier.create(query.findAllPaged(1, 2).collectList())
        .assertNext(items -> assertThat(items).hasSize(1))
        .verifyComplete();
  }

  @Test
  @DisplayName("findByCode returns system with all joined detail when system exists")
  void findByCode_existingSystem_returnsDetailWithResilience() {
    OutboundSystem saved = systemRepository.save(system("by-code-gw", "http://bycode.test")).block();
    resilienceRepository.save(resilience(saved.getId())).block();
    authRepository.save(auth(saved.getId())).block();

    StepVerifier.create(query.findByCode("by-code-gw"))
        .assertNext(detail -> {
          assertThat(detail.code()).isEqualTo("by-code-gw");
          assertThat(detail.resilience()).isNotNull();
          assertThat(detail.auth()).isNotNull();
          assertThat(detail.auth().tokenKeyName()).isEqualTo("X-API-Key");
          assertThat(detail.hostConfig()).isNull();
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findByCode returns null for absent optional configs when no configs are saved")
  void findByCode_systemWithoutOptionalConfigs_returnsNullForAllOptionals() {
    systemRepository.save(system("bare-gw", "http://bare.test")).block();

    StepVerifier.create(query.findByCode("bare-gw"))
        .assertNext(detail -> {
          assertThat(detail.code()).isEqualTo("bare-gw");
          assertThat(detail.resilience()).isNull();
          assertThat(detail.auth()).isNull();
          assertThat(detail.responseMapper()).isNull();
          assertThat(detail.hostConfig()).isNull();
          assertThat(detail.headers()).isEmpty();
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findAllPaged scrubs sensitive header values")
  void findAllPaged_sensitiveHeaders_areRedacted() {
    OutboundSystem saved = systemRepository.save(system("header-gw", "http://hdr.test")).block();
    headerRepository.save(OutboundHeaderConfiguration.builder()
        .outboundSystemId(saved.getId())
        .headers(Map.of("Authorization", "Bearer secret", "Content-Type", "application/json"))
        .build()).block();

    StepVerifier.create(query.findAllPaged(0, 10).collectList())
        .assertNext(items -> {
          assertThat(items).hasSize(1);
          OutboundSystemDetailResponse detail = items.get(0);
          assertThat(detail.headers()).hasSize(1);
          Map<String, Object> headerMap = detail.headers().get(0);
          assertThat(headerMap.get("Authorization")).isEqualTo("***");
          assertThat(headerMap.get("Content-Type")).isEqualTo("application/json");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findByCode returns empty Mono when code does not exist")
  void findByCode_nonExistentCode_returnsEmpty() {
    StepVerifier.create(query.findByCode("ghost-system"))
        .verifyComplete();
  }

  private OutboundSystem system(String code, String baseUrl) {
    return OutboundSystem.builder()
        .code(code)
        .name(code)
        .httpMethod("POST")
        .baseUrl(baseUrl)
        .apiPath("/api")
        .active(true)
        .idempotencyTtlHours(24)
        .build();
  }

  private OutboundResilienceConfiguration resilience(Long systemId) {
    return OutboundResilienceConfiguration.builder()
        .outboundSystemId(systemId)
        .readTimeOutMs(5000)
        .cbFailureRateThreshold(50f)
        .cbSlowCallRateThreshold(100f)
        .cbSlowCallDurationMs(60000)
        .cbPermittedInHalfOpen(10)
        .cbSlidingWindowSize(100)
        .cbWaitDurationOpenMs(60000)
        .bhMaxConcurrentCalls(25)
        .bhMaxWaitDurationMs(0)
        .rlLimitForPeriod(100)
        .rlLimitRefreshPeriodMs(1000)
        .rlTimeoutDurationMs(0)
        .retryMaxAttempts(3)
        .retryWaitDurationMs(500)
        .retryOnStatusCodes("500,502,503")
        .build();
  }

  private OutboundAuthConfiguration auth(Long systemId) {
    return OutboundAuthConfiguration.builder()
        .outboundSystemId(systemId)
        .authType(AuthType.API_KEY)
        .tokenKeyName("X-API-Key")
        .tokenInjectLocation(TokenInjectLocation.HEADER)
        .build();
  }

  private OutboundHostConfiguration hostConfig(String baseUrl) {
    return OutboundHostConfiguration.builder()
        .baseUrl(baseUrl)
        .connectTimeOutMs(5000)
        .maxConnections(50)
        .pendingAcquireMaxCount(100)
        .pendingAcquireTimeoutMs(5000)
        .disableSslVerification(false)
        .useProxy(false)
        .build();
  }
}
