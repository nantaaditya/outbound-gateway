package com.nantaaditya.example.helper;

import com.nantaaditya.example.model.dto.ContextDTO;
import com.nantaaditya.example.model.logger.AppLogMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
public class ContextHelper {

  private final Map<String, ContextDTO> contexts = new ConcurrentHashMap<>();
  private final Map<String, String> additionalContexts = new ConcurrentHashMap<>();

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
    return contexts.values().stream().toList();
  }

  public ContextDTO get(String requestId) {
    if (requestId == null) return null;
    try {
      return contexts.getOrDefault(requestId, null);
    } catch (Exception ex) {
      log.error(AppLogMessage.message("#Context - failed to get {}", requestId).error(ex));
      return null;
    }
  }

  public byte[] getAdditionalData(String requestId) {
    if (requestId == null) return null;
    String json = additionalContexts.getOrDefault(requestId, null);
    if (json == null) return null;

    return json.getBytes(StandardCharsets.UTF_8);
  }

  public void cleanUp(String requestId) {
    if (requestId != null) {
      contexts.remove(requestId);
      additionalContexts.remove(requestId);
    }
    MDC.clear();
  }

  public int size() {
    return contexts.size();
  }
}
