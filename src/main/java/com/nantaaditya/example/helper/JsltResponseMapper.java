package com.nantaaditya.example.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.properties.CacheProperties;
import com.schibsted.spt.data.jslt.Expression;
import com.schibsted.spt.data.jslt.Parser;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
public class JsltResponseMapper {

  private final ObjectMapper objectMapper;
  private final CacheProperties cacheProperties;
  private final MeterRegistry meterRegistry;

  private Cache<String, Expression> expressionCache;

  @EventListener(ApplicationReadyEvent.class)
  public void onStart() {
    this.expressionCache = CacheHelper.create(ConstantName.JSLT_CACHE,
        cacheProperties.get(ConstantName.JSLT_CACHE), meterRegistry);
  }

  /**
   * Applies the JSLT {@code script} to the JSON {@code body}.
   *
   * @param body   the raw JSON response body
   * @param script the JSLT transform script; when {@code null} or blank the original body is returned unchanged
   * @return the transformed JSON string on success; the original {@code body} when script is absent;
   *         or {@code null} when mapping fails — callers MUST null-check and treat {@code null} as a fallback signal
   */
  public String map(String body, String script) {
    if (script == null || script.isBlank()) {
      return body;
    }
    try {
      JsonNode input = objectMapper.readTree(body);
      Expression expression = expressionCache.get(script, Parser::compileString);
      JsonNode result = expression.apply(input);
      return objectMapper.writeValueAsString(result);
    } catch (Exception e) {
      log.warn(AppLogMessage.message("JSLT mapping failed: {}", e.getMessage()).error(e));
      return null;
    }
  }
}
