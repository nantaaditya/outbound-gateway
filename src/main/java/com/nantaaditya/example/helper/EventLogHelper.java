package com.nantaaditya.example.helper;

import com.google.gson.Gson;
import com.nantaaditya.example.entity.EventLog;
import com.nantaaditya.example.model.dto.ContextDTO;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.properties.LogProperties;
import com.nantaaditya.example.repository.EventLogRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Log4j2
@Component
@RequiredArgsConstructor
public class EventLogHelper {

  private final EventLogRepository eventLogRepository;
  private final LogProperties logProperties;
  private final Gson gson;
  private final ContextHelper contextHelper;

  public void save(ServerWebExchange exchange, ContextDTO context) {
    try {
      if (context == null) {
        log.warn(AppLogMessage.message("#EventLog - context is null"));
        return;
      }

      byte[] additionalData = contextHelper.getAdditionalData(context.getRequestId());

      if (logProperties.isIgnoredTraceLogPath(context.getPath())) {
        log.debug(AppLogMessage.message("#EventLog - ignored trace log path"));
        contextHelper.cleanUp(context.getRequestId());
        return;
      }

      byte[] cachedBody = (byte[]) exchange.getAttribute("cachedRequestBody");
      String payload = new String(cachedBody, StandardCharsets.UTF_8);
      String cleanedPayload = GsonHelper.cleanJson(payload, gson);

      Mono.fromSupplier(() -> createEventLog(context, additionalData, cleanedPayload))
          .flatMap(eventLogRepository::save)
          .subscribe(
              success -> log.debug(AppLogMessage.message("#EventLog - success save event log {}", context.getRequestId())),
              error -> log.error(AppLogMessage.message("#EventLog - error save event log {}, cause {}",
                  context.getRequestId(), error.getMessage()).error(error)),
              () -> contextHelper.cleanUp(context.getRequestId())
          );
    } catch (Exception e) {
      log.error(AppLogMessage.message("#EventLog - failed save event log {}, cause {}", context.getRequestId(), e.getMessage()).error(e));
    }
  }

  private EventLog createEventLog(ContextDTO context, byte[] additionalData, String payload) {
    return EventLog.builder()
        .id(TsidHelper.generateStringId())
        .clientId(context.getClientId())
        .requestId(context.getRequestId())
        .method(context.getMethod())
        .path(context.getPath())
        .responseCode(context.getResponseCode())
        .responseDescription(context.getResponseDescription())
        .payload(payload.getBytes(StandardCharsets.UTF_8))
        .additionalData(additionalData)
        .createdDate(LocalDateTime.now())
        .build();
  }
}
