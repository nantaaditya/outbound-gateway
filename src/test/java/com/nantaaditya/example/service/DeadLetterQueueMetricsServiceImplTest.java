package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.model.dto.DlqDepthRow;
import com.nantaaditya.example.persistence.CustomQueryHelper;
import com.nantaaditya.example.service.impl.DeadLetterQueueMetricsServiceImpl;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class DeadLetterQueueMetricsServiceImplTest {

  @Mock private CustomQueryHelper customQueryHelper;

  private SimpleMeterRegistry meterRegistry;
  private DeadLetterQueueMetricsServiceImpl service;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    service = new DeadLetterQueueMetricsServiceImpl(customQueryHelper, meterRegistry);
  }

  @Test
  @DisplayName("poll registers outbox_dlq_depth and outbox_dlq_oldest_ts_seconds gauges per process group")
  void poll_withRows_registersGaugesPerGroup() {
    LocalDateTime oldest = LocalDateTime.of(2026, 8, 1, 0, 0);
    when(customQueryHelper.countUnprocessedDeadLetterProcesses())
        .thenReturn(Flux.just(
            new DlqDepthRow("retry", "retry-payment", 3L, oldest),
            new DlqDepthRow("retry", "retry-refund", 1L, oldest)));

    service.pollDeadLetterQueue();

    Gauge depthGauge = meterRegistry.find("outbox_dlq_depth")
        .tags("process_type", "retry", "process_name", "retry-payment")
        .gauge();
    assertThat(depthGauge).isNotNull();
    assertThat(depthGauge.value()).isEqualTo(3.0);

    Gauge oldestTsGauge = meterRegistry.find("outbox_dlq_oldest_ts_seconds")
        .tags("process_type", "retry", "process_name", "retry-payment")
        .gauge();
    assertThat(oldestTsGauge).isNotNull();
    assertThat(oldestTsGauge.value())
        .isEqualTo((double) oldest.toEpochSecond(ZoneOffset.UTC));
  }

  @Test
  @DisplayName("poll with empty result removes gauges from previous poll")
  void poll_withEmptyResult_removesStaleGauges() {
    when(customQueryHelper.countUnprocessedDeadLetterProcesses())
        .thenReturn(Flux.just(
            new DlqDepthRow("retry", "retry-payment", 3L, LocalDateTime.of(2026, 8, 1, 0, 0))))
        .thenReturn(Flux.empty());

    service.pollDeadLetterQueue();
    assertThat(meterRegistry.find("outbox_dlq_depth").gauge()).isNotNull();

    service.pollDeadLetterQueue();
    assertThat(meterRegistry.find("outbox_dlq_depth").gauge()).isNull();
  }

  @Test
  @DisplayName("poll with repository failure logs and never throws")
  void poll_repositoryError_doesNotThrow() {
    when(customQueryHelper.countUnprocessedDeadLetterProcesses())
        .thenReturn(Flux.error(new RuntimeException("db down")));

    assertThatCode(() -> service.pollDeadLetterQueue()).doesNotThrowAnyException();
    assertThat(meterRegistry.find("outbox_dlq_depth").gauge()).isNull();
  }

  @Test
  @DisplayName("re-poll with changed depth updates the gauge value for a persistent group")
  void poll_repeatedWithChangedDepth_updatesGaugeValue() {
    when(customQueryHelper.countUnprocessedDeadLetterProcesses())
        .thenReturn(Flux.just(
            new DlqDepthRow("retry", "retry-payment", 3L, LocalDateTime.of(2026, 8, 1, 0, 0))))
        .thenReturn(Flux.just(
            new DlqDepthRow("retry", "retry-payment", 5L, LocalDateTime.of(2026, 8, 1, 0, 0))));

    service.pollDeadLetterQueue();
    assertThat(meterRegistry.find("outbox_dlq_depth")
        .tags("process_type", "retry", "process_name", "retry-payment")
        .gauge().value()).isEqualTo(3.0);

    service.pollDeadLetterQueue();
    assertThat(meterRegistry.find("outbox_dlq_depth")
        .tags("process_type", "retry", "process_name", "retry-payment")
        .gauge().value()).isEqualTo(5.0);
  }

  @Test
  @DisplayName("poll with null oldest_created_date keeps the depth gauge and skips the oldest-ts gauge")
  void poll_nullOldestDate_skipsOldestTsGauge() {
    when(customQueryHelper.countUnprocessedDeadLetterProcesses())
        .thenReturn(Flux.just(new DlqDepthRow("retry", "retry-payment", 3L, null)));

    service.pollDeadLetterQueue();

    assertThat(meterRegistry.find("outbox_dlq_depth")
        .tags("process_type", "retry", "process_name", "retry-payment").gauge()).isNotNull();
    assertThat(meterRegistry.find("outbox_dlq_oldest_ts_seconds").gauge()).isNull();
  }
}
