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
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
  }

  @Test
  @DisplayName("count returns total number of rows in v_outbound_system_detail view")
  void count_withTwoSystems_returnsTwo() {
    Long hostId1 = hostConfigRepository.save(hostConfig("http://count-1.test")).block().getId();
    Long hostId2 = hostConfigRepository.save(hostConfig("http://count-2.test")).block().getId();
    systemRepository.save(system("count-gw-1", hostId1)).block();
    systemRepository.save(system("count-gw-2", hostId2)).block();

    StepVerifier.create(query.count())
        .assertNext(count -> assertThat(count).isEqualTo(2))
        .verifyComplete();
  }

  @Test
  @DisplayName("findAllPaged returns page of systems with their joined config details")
  void findAllPaged_withFullyConfiguredSystem_returnsDetailWithAllConfigs() {
    Long hostConfigId = hostConfigRepository.save(hostConfig("http://detail.test")).block().getId();
    OutboundSystem saved = systemRepository.save(system("detail-gw", hostConfigId)).block();
    resilienceRepository.save(resilience(saved.getId())).block();
    authRepository.save(auth(saved.getId())).block();

    StepVerifier.create(query.findAllPaged(0, 10).collectList())
        .assertNext(items -> {
          assertThat(items).hasSize(1);
          OutboundSystemDetailResponse detail = items.get(0);
          assertThat(detail.code()).isEqualTo("detail-gw");
          assertThat(detail.resilience()).isNotNull();
          assertThat(detail.resilience().readTimeOutMs()).isEqualTo(5000);
          assertThat(detail.authentication()).isNotNull();
          assertThat(detail.authentication().authType()).isEqualTo(AuthType.API_KEY);
          assertThat(detail.hostConfiguration()).isNotNull();
          assertThat(detail.hostConfiguration().maxConnections()).isEqualTo(50);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findAllPaged respects page and size parameters")
  void findAllPaged_pagination_returnsCorrectPage() {
    for (int i = 1; i <= 3; i++) {
      Long hostId = hostConfigRepository.save(hostConfig("http://page-" + i + ".test")).block().getId();
      systemRepository.save(system("page-gw-" + i, hostId)).block();
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
    Long hostConfigId = hostConfigRepository.save(hostConfig("http://bycode.test")).block().getId();
    OutboundSystem saved = systemRepository.save(system("by-code-gw", hostConfigId)).block();
    resilienceRepository.save(resilience(saved.getId())).block();
    authRepository.save(auth(saved.getId())).block();

    StepVerifier.create(query.findByCode("by-code-gw"))
        .assertNext(detail -> {
          assertThat(detail.code()).isEqualTo("by-code-gw");
          assertThat(detail.resilience()).isNotNull();
          assertThat(detail.authentication()).isNotNull();
          assertThat(detail.authentication().tokenKeyName()).isEqualTo("X-API-Key");
          // host_configuration_id is now a mandatory FK (V23/V25) — the join is an INNER JOIN,
          // so hostConfiguration is always present, unlike the old base_url LEFT JOIN.
          assertThat(detail.hostConfiguration()).isNotNull();
          assertThat(detail.hostConfiguration().baseUrl()).isEqualTo("http://bycode.test");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findByCode returns null for absent optional configs when no configs are saved")
  void findByCode_systemWithoutOptionalConfigs_returnsNullForAllOptionals() {
    Long hostConfigId = hostConfigRepository.save(hostConfig("http://bare.test")).block().getId();
    systemRepository.save(system("bare-gw", hostConfigId)).block();

    StepVerifier.create(query.findByCode("bare-gw"))
        .assertNext(detail -> {
          assertThat(detail.code()).isEqualTo("bare-gw");
          assertThat(detail.resilience()).isNull();
          assertThat(detail.authentication()).isNull();
          assertThat(detail.responseMapper()).isNull();
          // host_configuration_id is a mandatory FK — hostConfiguration is never null now.
          assertThat(detail.hostConfiguration()).isNotNull();
          assertThat(detail.hostConfiguration().baseUrl()).isEqualTo("http://bare.test");
          assertThat(detail.headers()).isEmpty();
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findAllPaged scrubs sensitive header values")
  void findAllPaged_sensitiveHeaders_areRedacted() {
    Long hostConfigId = hostConfigRepository.save(hostConfig("http://hdr.test")).block().getId();
    OutboundSystem saved = systemRepository.save(system("header-gw", hostConfigId)).block();
    headerRepository.save(OutboundHeaderConfiguration.builder()
        .outboundSystemId(saved.getId())
        .headers(Map.of("Authorization", "Bearer secret", "Content-Type", "application/json"))
        .build()).block();

    StepVerifier.create(query.findAllPaged(0, 10).collectList())
        .assertNext(items -> {
          assertThat(items).hasSize(1);
          OutboundSystemDetailResponse detail = items.get(0);
          assertThat(detail.headers()).hasSize(2);
          assertThat(detail.headers()).containsEntry("Authorization","***");
          assertThat(detail.headers()).containsEntry("Content-Type", "application/json");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("findByCode returns empty Mono when code does not exist")
  void findByCode_nonExistentCode_returnsEmpty() {
    StepVerifier.create(query.findByCode("ghost-system"))
        .verifyComplete();
  }

  private OutboundSystem system(String code, Long hostConfigurationId) {
    return OutboundSystem.builder()
        .code(code)
        .name(code)
        .httpMethod("POST")
        .hostConfigurationId(hostConfigurationId)
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
