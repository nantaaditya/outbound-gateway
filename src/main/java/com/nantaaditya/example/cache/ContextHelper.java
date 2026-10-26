package com.nantaaditya.example.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.dto.ContextDTO;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.properties.CacheProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map.Entry;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
public class ContextHelper {

  private final CacheProperties cacheProperties;
  private final MeterRegistry meterRegistry;

  private Cache<String, ContextDTO> contexts;
  private Cache<String, String> additionalContexts;

  @EventListener(ApplicationReadyEvent.class)
  public void onStart() {
    this.contexts = CacheHelper.create(ConstantName.CONTEXTS_CACHE,
        cacheProperties.get(ConstantName.CONTEXTS_CACHE), meterRegistry);
    this.additionalContexts = CacheHelper.create(ConstantName.ADDITIONAL_DATA_CACHE,
        cacheProperties.get(ConstantName.ADDITIONAL_DATA_CACHE), meterRegistry);
  }

  public void put(ContextDTO contextDTO) {
    try {
      contexts.put(contextDTO.getRequestId(), contextDTO);
    } catch (Exception ex) {
      log.error(AppLogMessage.message("#Context - failed to save {}", contextDTO.getRequestId()).error(ex));
    }
  }

  public void update(String requestId, UnaryOperator<ContextDTO> contextFunction) {
    ContextDTO contextDTO = get(requestId);
    if (contextDTO != null) {
      contextDTO = contextFunction.apply(contextDTO);
      put(contextDTO);
    }
  }

  public void put(String requestId, String additionalData) {
    try {
      additionalContexts.put(requestId, additionalData);
    } catch (Exception ex) {
      log.error(AppLogMessage.message("#Context - failed to update {}", requestId).error(ex));
    }
  }

  public List<ContextDTO> list() {
    return contexts.asMap().entrySet()
        .stream()
        .map(Entry::getValue)
        .toList();
  }

  public ContextDTO get(String requestId) {
    if (requestId == null) return null;
    try {
      return contexts.getIfPresent(requestId);
    } catch (Exception ex) {
      log.error(AppLogMessage.message("#Context - failed to get {}", requestId).error(ex));
      return null;
    }
  }

  public byte[] getAdditionalData(String requestId) {
    if (requestId == null) return null; //NOSONAR
    String json = additionalContexts.getIfPresent(requestId);
    if (json == null) return null; //NOSONAR

    return json.getBytes(StandardCharsets.UTF_8);
  }

  public void cleanUp(String requestId) {
    if (requestId != null) {
      contexts.invalidate(requestId);
      additionalContexts.invalidate(requestId);
    }
    MDC.clear();
  }

  public long size() {
    return contexts.estimatedSize();
  }
}
