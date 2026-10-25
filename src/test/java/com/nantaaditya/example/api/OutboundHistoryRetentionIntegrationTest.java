package com.nantaaditya.example.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

class OutboundHistoryRetentionIntegrationTest extends BaseIntegrationTest {

  private static final String ENDPOINT = "/internal-api/outbound_history";

  @Autowired
  private OutboundSystemRepository systemRepository;
  @Autowired
  private OutboundHostConfigurationRepository hostConfigRepository;
  @Autowired
  private OutboundHistoryRepository historyRepository;
  @Autowired
  private DatabaseClient databaseClient;

  private long systemId;

  @BeforeEach
  void seed() {
    OutboundHostConfiguration host = hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl("https://retention.example.test")
        .connectTimeOutMs(5000)
        .maxConnections(10)
        .pendingAcquireMaxCount(10)
        .pendingAcquireTimeoutMs(5000)
        .disableSslVerification(false)
        .useProxy(false)
        .build()).block();
    OutboundSystem system = systemRepository.save(OutboundSystem.builder()
        .code("retention-test")
        .name("Retention Test")
        .httpMethod("POST")
        .hostConfigurationId(host.getId())
        .apiPath("/v1/x")
        .active(true)
        .idempotencyTtlHours(24)
        .build()).block();
    systemId = system.getId();

    saveHistory("old-1", 40);
    saveHistory("old-2", 31);
    saveHistory("recent-1", 5);
    saveHistory("recent-2", 0);
  }

  @AfterEach
  void cleanup() {
    historyRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
  }

  @Test
  @DisplayName("DELETE removes histories older than the given days and keeps newer ones")
  void remove_deletesOnlyObsoleteHistories() {
    webTestClient.delete()
        .uri(ENDPOINT + "?days=30")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.data").isEqualTo(true);

    // the purge runs as a background task, so wait for it
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(remainingKeys()).containsExactlyInAnyOrder("recent-1", "recent-2"));
  }

  @Test
  @DisplayName("days=0 is rejected with 400 and nothing is deleted")
  void remove_zeroDays_rejectedAndNothingDeleted() {
    webTestClient.delete()
        .uri(ENDPOINT + "?days=0")
        .exchange()
        .expectStatus().isBadRequest();

    assertThat(remainingKeys()).hasSize(4);
  }

  @Test
  @DisplayName("negative days is rejected with 400 and nothing is deleted")
  void remove_negativeDays_rejectedAndNothingDeleted() {
    webTestClient.delete()
        .uri(ENDPOINT + "?days=-5")
        .exchange()
        .expectStatus().isBadRequest();

    assertThat(remainingKeys()).hasSize(4);
  }

  @Test
  @DisplayName("missing days parameter is rejected with 400 and nothing is deleted")
  void remove_missingDays_rejectedAndNothingDeleted() {
    webTestClient.delete()
        .uri(ENDPOINT)
        .exchange()
        .expectStatus().isBadRequest();

    assertThat(remainingKeys()).hasSize(4);
  }

  private void saveHistory(String key, int ageDays) {
    historyRepository.save(OutboundHistory.builder()
        .outboundSystemId(systemId)
        .idempotencyKey(key)
        .callerService("test-svc")
        .requestMethod("POST")
        .requestUrl("https://retention.example.test/v1/x")
        .requestPayload("{}")
        .responseStatus(200)
        .responsePayload("{}")
        .durationMs(10)
        .build()).block();
    databaseClient.sql("UPDATE outbound_histories SET created_date = NOW() - make_interval(days => :d) "
            + "WHERE idempotency_key = :k")
        .bind("d", ageDays)
        .bind("k", key)
        .fetch().rowsUpdated().block();
  }

  private List<String> remainingKeys() {
    return historyRepository.findAll()
        .map(OutboundHistory::getIdempotencyKey)
        .collectList()
        .block();
  }
}
