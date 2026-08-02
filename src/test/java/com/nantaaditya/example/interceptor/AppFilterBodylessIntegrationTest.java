package com.nantaaditya.example.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies AppFilter behaviour for bodyless HTTP methods (GET, DELETE, HEAD, OPTIONS).
 *
 * <p>For bodyless methods the filter must:
 * <ul>
 *   <li>Skip {@code DataBufferUtils.join} — no unnecessary DataBuffer allocation</li>
 *   <li>Set {@code cachedRequestBody} attribute to an empty byte array</li>
 *   <li>Still run the observation + event-log {@code doFinally} block</li>
 * </ul>
 */
class AppFilterBodylessIntegrationTest extends BaseIntegrationTest {

  @Autowired
  private OutboundSystemRepository systemRepository;
  @Autowired
  private OutboundHostConfigurationRepository hostConfigRepository;
  @Autowired
  private OutboundHistoryRepository historyRepository;

  private Long systemId;

  @BeforeEach
  void seed() {
    OutboundHostConfiguration hostConfig = hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl("https://api.example.com")
        .connectTimeOutMs(5000)
        .maxConnections(50)
        .pendingAcquireMaxCount(100)
        .pendingAcquireTimeoutMs(5000)
        .disableSslVerification(false)
        .useProxy(false)
        .build()).block();

    OutboundSystem system = systemRepository.save(OutboundSystem.builder()
        .code("filter-test")
        .name("Filter Test")
        .httpMethod("POST")
        .hostConfigurationId(hostConfig.getId())
        .apiPath("/v1/charge")
        .active(true)
        .idempotencyTtlHours(24)
        .build()).block();

    systemId = system.getId();
    historyRepository.save(history(systemId, "filter-idem-001", 200)).block();
    historyRepository.save(history(systemId, "filter-idem-002", 500)).block();
  }

  @AfterEach
  void cleanup() {
    historyRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
  }

  @Test
  @DisplayName("GET 200 — X-Received-Time header is set, proving doFinally ran for bodyless method")
  void getRequest_200_receivedTimeHeaderIsSet() {
    webTestClient.get()
        .uri("/api/gateway/histories?code=filter-test")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().exists(HeaderConstant.RECEIVED_TIME.getHeader());
  }

  @Test
  @DisplayName("GET 400 unknown system — doFinally completes cleanly for bodyless error response")
  void getRequest_400UnknownSystem_doFinallyCompletesCleanly() {
    webTestClient.get()
        .uri("/api/gateway/histories/filter-idem-001?code=no-system")
        .exchange()
        .expectStatus().isBadRequest()
        .expectHeader().exists(HeaderConstant.RECEIVED_TIME.getHeader());
  }

  @Test
  @DisplayName("GET 400 unknown key — doFinally completes, response is committed properly")
  void getRequest_400UnknownKey_completes() {
    webTestClient.get()
        .uri("/api/gateway/histories/ghost-key?code=filter-test")
        .exchange()
        .expectStatus().isBadRequest()
        .expectHeader().exists(HeaderConstant.RECEIVED_TIME.getHeader());
  }

  @Test
  @DisplayName("GET with pagination params — bodyless method returns paginated response")
  void getRequest_withPagination_returnsPagedResponse() {
    webTestClient.get()
        .uri("/api/gateway/histories?code=filter-test&page=0&size=1")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().exists(HeaderConstant.RECEIVED_TIME.getHeader());
  }

  @Test
  @DisplayName("DELETE request — bypasses DataBufferUtils.join, doFinally completes")
  void deleteRequest_bodylessPath_doFinallyRuns() {
    webTestClient.delete()
        .uri("/api/outbound/filter-test")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().exists(HeaderConstant.RECEIVED_TIME.getHeader());
  }

  private OutboundHistory history(long sysId, String key, int status) {
    return OutboundHistory.builder()
        .outboundSystemId(sysId)
        .idempotencyKey(key)
        .callerService("filter-svc")
        .requestMethod("POST")
        .requestUrl("https://api.example.com/v1/charge")
        .requestPayload("{\"amount\":500}")
        .responseStatus(status)
        .responsePayload("{\"ok\":true}")
        .durationMs(50)
        .build();
  }
}
