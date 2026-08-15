package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.helper.CustomQueryHelper;
import com.nantaaditya.example.model.dto.DlqDepthRow;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.service.DeadLetterQueueMetricsService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Exposes dead-letter queue depth as Prometheus gauges:
 * {@code outbox_dlq_depth} and {@code outbox_dlq_oldest_ts_seconds},
 * one series per {@code (process_type, process_name)} group.
 *
 * <p>{@link MultiGauge} replaces the whole gauge set on each poll, so groups that
 * drain between polls disappear from the scrape instead of going stale.
 *
 * <p>Failures are logged, never thrown — a metrics poll must not kill the
 * scheduling thread or surface as an application error.
 */
@Log4j2
@Service
public class DeadLetterQueueMetricsServiceImpl implements DeadLetterQueueMetricsService {

  private static final String DLQ_DEPTH_GAUGE = "outbox_dlq_depth";
  private static final String DLQ_OLDEST_TS_GAUGE = "outbox_dlq_oldest_ts_seconds";
  // Bounds a hung aggregate query so overlapping polls cannot stack on the scheduler.
  private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);

  private final CustomQueryHelper customQueryHelper;
  private final MultiGauge depthGauge;
  private final MultiGauge oldestTsGauge;

  public DeadLetterQueueMetricsServiceImpl(CustomQueryHelper customQueryHelper, MeterRegistry meterRegistry) {
    this.customQueryHelper = customQueryHelper;
    this.depthGauge = MultiGauge.builder(DLQ_DEPTH_GAUGE).register(meterRegistry);
    this.oldestTsGauge = MultiGauge.builder(DLQ_OLDEST_TS_GAUGE).register(meterRegistry);
  }

  @Override
  @Scheduled(fixedDelayString = "${gateway.metrics.dlq.poll-interval-ms:30000}",
      initialDelayString = "${gateway.metrics.dlq.initial-delay-ms:5000}")
  public void pollDeadLetterQueue() {
    customQueryHelper.countUnprocessedDeadLetterProcesses()
        .collectList()
        .timeout(QUERY_TIMEOUT)
        .subscribe(
            this::updateGauges,
            error -> log.warn(AppLogMessage.message(
                "#DLQ-METRICS - failed to poll dead letter queue: {}", error.getMessage()))
        );
  }

  private void updateGauges(List<DlqDepthRow> rows) {
    // Build both row sets first so a mapping failure cannot leave one gauge half-updated.
    List<MultiGauge.Row<Number>> depthRows = rows.stream().map(this::depthRow).toList();
    List<MultiGauge.Row<Number>> oldestTsRows = rows.stream()
        .filter(row -> row.oldestCreatedDate() != null)
        .map(this::oldestTsRow)
        .toList();
    // overwrite=true: without it MultiGauge keeps the value object captured at first
    // registration, so a persistent group would report a frozen depth forever.
    depthGauge.register(depthRows, true);
    oldestTsGauge.register(oldestTsRows, true);
  }

  private MultiGauge.Row<Number> depthRow(DlqDepthRow row) {
    return MultiGauge.Row.of(dlqTags(row), row.depth());
  }

  private MultiGauge.Row<Number> oldestTsRow(DlqDepthRow row) {
    // Epoch seconds so the central stack computes staleness as time() - value,
    // which stays accurate between polls instead of drifting with poll cadence.
    return MultiGauge.Row.of(dlqTags(row), row.oldestCreatedDate().toEpochSecond(ZoneOffset.UTC));
  }

  private static Tags dlqTags(DlqDepthRow row) {
    return Tags.of("process_type", row.processType(), "process_name", row.processName());
  }
}
