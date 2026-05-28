package com.nantaaditya.example.helper;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.nantaaditya.example.model.dto.ContextDTO;
import com.nantaaditya.example.model.logger.AppLogMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
public class ContextHelper {

  private final Cache<String, ContextDTO> contexts = Caffeine.newBuilder()
      .expireAfterWrite(10, TimeUnit.MINUTES)
      .evictionListener((RemovalListener<String, ContextDTO>) (key, value, cause)
          -> log.debug("#Context - remove key: {}, cause: {}", key, cause))
      .build();

  private final Cache<String, String> additionalContexts = Caffeine.newBuilder()
      .expireAfterWrite(10, TimeUnit.MINUTES)
      .evictionListener((RemovalListener<String, String>) (key, value, cause)
          -> log.debug("#Context - remove key: {}, cause: {}", key, cause))
      .build();

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
