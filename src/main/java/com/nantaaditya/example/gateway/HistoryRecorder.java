package com.nantaaditya.example.gateway;

import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.model.dto.ContextDTO;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.response.GatewayResponse;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Stores the outcome of an outbound call in {@code outbound_histories}, which serves both auditing and the
 * idempotency replay. Recording never fails the call: any error is logged and swallowed, so a history write
 * problem cannot surface to the caller as a gateway failure.
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class HistoryRecorder {

  private final OutboundHistoryRepository historyRepository;
  private final JsonHelper jsonHelper;
  private final GatewayHelper gatewayHelper;

  /**
   * @return a Mono that completes once the row is stored, or once the attempt failed (logged, not propagated)
   */
  public Mono<Void> record(OutboundSystemContext context, String callerService, String idempotencyKey,
      GatewayRequest request, GatewayResponse response, long durationMs, int retryCount) {
    return Mono.deferContextual(ctx -> {
          String requestId = ctx.<ContextDTO>getOrEmpty("context")
              .map(ContextDTO::getRequestId)
              .orElse(null);
          // the ORIGINAL request: parameters injected into the outbound body are deliberately not stored
          String requestPayload = request.bodyAsString(jsonHelper);
          Map<String, Object> requestHeaders = gatewayHelper.resolveRequestHeaders(context);
          OutboundHistory history = OutboundHistory.create(context.getSystem(),
              context.getHostConfig().getBaseUrl(), callerService,
              idempotencyKey, requestId, requestPayload,
              requestHeaders, response, durationMs, retryCount
          );

          return historyRepository.saveHistory(history).then();
        })
        .onErrorResume(err -> {
          log.error(AppLogMessage.message("#GATEWAY - failed to save history for {}: {}",
              gatewayHelper.code(context), err.getMessage()).error(err));
          return Mono.empty();
        });
  }
}
