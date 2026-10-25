package com.nantaaditya.example.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nantaaditya.example.model.constant.CacheType;
import com.nantaaditya.example.properties.CacheProperties;
import com.nantaaditya.example.properties.embedded.CacheConfiguration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JsltResponseMapperTest {

  private JsltResponseMapper mapper;

  @Mock
  private CacheProperties cacheProperties;

  @BeforeEach
  void setUp() {
    when(cacheProperties.get(anyString()))
        .thenReturn(new CacheConfiguration(CacheType.SIZE, 100, 0, TimeUnit.MILLISECONDS));
    mapper = new JsltResponseMapper(new ObjectMapper(), cacheProperties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    mapper.onStart();
  }

  @Test
  @DisplayName("map with null script returns original body unchanged")
  void map_nullScript_returnsOriginalBody() {
    String body = "{\"status\":\"success\",\"amount\":10000}";

    String result = mapper.map(body, null);

    assertThat(result).isEqualTo(body);
  }

  @Test
  @DisplayName("map with empty script returns original body unchanged")
  void map_emptyScript_returnsOriginalBody() {
    String body = "{\"status\":\"success\"}";

    String result = mapper.map(body, "");

    assertThat(result).isEqualTo(body);
  }

  @Test
  @DisplayName("map with JSLT script transforms body correctly")
  void map_validScript_transformsBody() {
    String body = "{\"status\":\"success\",\"amount\":10000,\"currency\":\"IDR\"}";
    String script = "{\"code\": .status, \"value\": .amount}";

    String result = mapper.map(body, script);

    assertThat(result)
        .contains("\"code\"")
        .contains("\"success\"")
        .contains("\"value\"")
        .contains("10000")
        .doesNotContain("currency");
  }

  @Test
  @DisplayName("map with invalid body returns null to signal failure")
  void map_invalidJson_returnsNull() {
    String invalidBody = "not-json";
    String script = "{\"code\": .status}";

    String result = mapper.map(invalidBody, script);

    assertThat(result).isNull();
  }

  @Test
  @DisplayName("map with invalid JSLT script returns null to signal failure")
  void map_invalidScript_returnsNull() {
    String body = "{\"status\":\"ok\"}";
    String brokenScript = "not valid jslt {{{";

    String result = mapper.map(body, brokenScript);

    assertThat(result).isNull();
  }
}
