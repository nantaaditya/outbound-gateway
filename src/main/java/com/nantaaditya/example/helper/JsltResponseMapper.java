package com.nantaaditya.example.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.schibsted.spt.data.jslt.Expression;
import com.schibsted.spt.data.jslt.Parser;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
public class JsltResponseMapper {

  private static final int MAX_CACHE_SIZE = 200;

  private final ObjectMapper objectMapper;
  private final Cache<String, Expression> expressionCache = Caffeine.newBuilder()
      .maximumSize(MAX_CACHE_SIZE)
      .build();

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
      log.warn(AppLogMessage.message("JSLT mapping failed, returning original body: {}", e.getMessage()).error(e));
      return body;
    }
  }
}
