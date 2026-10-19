package com.nantaaditya.example.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.request.admin.UpsertHeadersRequest;
import com.nantaaditya.example.model.request.admin.UpsertResponseMapperRequest;
import com.nantaaditya.example.repository.OutboundHeaderConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResponseMapperConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.OutboundService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Fires several first-time upserts for the same system at once. Before V12 + the retry in
 * OutboundServiceImpl every caller inserted its own row (duplicates); now exactly one row must remain
 * and every caller must succeed.
 */
class AdminUpsertConcurrencyIntegrationTest extends BaseIntegrationTest {

  // Each retry round lets at least one contender through, so 3 retries cover up to 4 simultaneous callers.
  private static final int CALLERS = 4;
  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired private OutboundService outboundService;
  @Autowired private OutboundSystemRepository systemRepository;
  @Autowired private OutboundHostConfigurationRepository hostConfigRepository;
  @Autowired private OutboundResponseMapperConfigurationRepository mapperRepository;
  @Autowired private OutboundHeaderConfigurationRepository headerRepository;

  private String code;
  private long systemId;

  @BeforeEach
  void seed() {
    code = "concurrent-" + SEQ.incrementAndGet();
    OutboundHostConfiguration host = hostConfigRepository.save(OutboundHostConfiguration.builder()
        .baseUrl("http://concurrency-" + code + ".example.test")
        .connectTimeOutMs(5000).maxConnections(10).pendingAcquireMaxCount(10)
        .pendingAcquireTimeoutMs(5000).disableSslVerification(false).useProxy(false)
        .build()).block();
    systemId = systemRepository.save(OutboundSystem.builder()
        .code(code).name(code).httpMethod("POST").hostConfigurationId(host.getId())
        .apiPath("/v1/x").active(true).idempotencyTtlHours(24).build()).block().getId();
  }

  @AfterEach
  void cleanup() {
    mapperRepository.deleteAll().block();
    headerRepository.deleteAll().block();
    systemRepository.deleteAll().block();
    hostConfigRepository.deleteAll().block();
  }

  @RepeatedTest(3)
  @DisplayName("concurrent first-time response-mapper upserts all succeed and leave exactly one row")
  void upsertResponseMapper_concurrent_singleRow() {
    UpsertResponseMapperRequest request = new UpsertResponseMapperRequest(ConverterType.NONE, null, null, true);

    List<Boolean> results = concurrently(() -> outboundService.upsertResponseMapper(code, request));

    assertThat(results).hasSize(CALLERS).containsOnly(Boolean.TRUE);
    List<OutboundResponseMapperConfiguration> rows =
        mapperRepository.findAll().filter(r -> r.getOutboundSystemId() == systemId).collectList().block();
    assertThat(rows).hasSize(1);
  }

  @RepeatedTest(3)
  @DisplayName("concurrent first-time headers upserts all succeed and leave exactly one row")
  void upsertHeaders_concurrent_singleRow() {
    UpsertHeadersRequest request = new UpsertHeadersRequest(Map.of("X-A", List.of("1")));

    List<Boolean> results = concurrently(() -> outboundService.upsertHeaders(code, request));

    assertThat(results).hasSize(CALLERS).containsOnly(Boolean.TRUE);
    List<OutboundHeaderConfiguration> rows =
        headerRepository.findByOutboundSystemId(systemId).collectList().block();
    assertThat(rows).hasSize(1);
  }

  private List<Boolean> concurrently(Supplier<Mono<Boolean>> call) {
    return Flux.range(0, CALLERS)
        .flatMap(i -> Mono.defer(call).subscribeOn(Schedulers.parallel()), CALLERS)
        .collectList()
        .block();
  }
}
