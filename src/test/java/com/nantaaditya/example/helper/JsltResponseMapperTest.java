package com.nantaaditya.example.helper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsltResponseMapperTest {

  private JsltResponseMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new JsltResponseMapper(new ObjectMapper());
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

    assertThat(result).contains("\"code\"");
    assertThat(result).contains("\"success\"");
    assertThat(result).contains("\"value\"");
    assertThat(result).contains("10000");
    assertThat(result).doesNotContain("currency");
  }

  @Test
  @DisplayName("map with invalid body returns original body")
  void map_invalidJson_returnsOriginalBody() {
    String invalidBody = "not-json";
    String script = "{\"code\": .status}";

    String result = mapper.map(invalidBody, script);

    assertThat(result).isEqualTo(invalidBody);
  }
}
