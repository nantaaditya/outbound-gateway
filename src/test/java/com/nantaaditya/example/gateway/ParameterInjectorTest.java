package com.nantaaditya.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nantaaditya.example.model.constant.ParameterInjectLocation;
import com.nantaaditya.example.model.dto.ResolvedParameter;
import com.nantaaditya.example.model.exception.InvalidParameterException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParameterInjectorTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ParameterInjector injector = new ParameterInjector(objectMapper);

  private static ResolvedParameter body(String path, String value) {
    return new ResolvedParameter("k", ParameterInjectLocation.BODY, path, value);
  }

  @Test
  @DisplayName("injectBody sets a top-level field")
  void injectBody_topLevel_setsField() {
    Map<String, Object> original = new LinkedHashMap<>(Map.of("amount", 100));

    JsonNode result = (JsonNode) injector.injectBody(original, List.of(body("token", "B")));

    assertThat(result.get("token").asText()).isEqualTo("B");
    assertThat(result.get("amount").asInt()).isEqualTo(100);
  }

  @Test
  @DisplayName("injectBody creates missing intermediate objects for a nested path")
  void injectBody_nestedPath_createsIntermediateObjects() {
    JsonNode result = (JsonNode) injector.injectBody(
        new LinkedHashMap<>(Map.of("amount", 1)), List.of(body("meta.auth.token", "B")));

    assertThat(result.at("/meta/auth/token").asText()).isEqualTo("B");
  }

  @Test
  @DisplayName("injectBody overwrites an existing value at the path")
  void injectBody_existingValue_isOverwritten() {
    JsonNode result = (JsonNode) injector.injectBody(
        new LinkedHashMap<>(Map.of("token", "client-supplied")), List.of(body("token", "B")));

    assertThat(result.get("token").asText()).isEqualTo("B");
  }

  @Test
  @DisplayName("injectBody does not mutate the original body")
  void injectBody_doesNotMutateOriginal() {
    Map<String, Object> original = new LinkedHashMap<>(Map.of("amount", 100));

    injector.injectBody(original, List.of(body("token", "B")));

    assertThat(original).containsOnlyKeys("amount");
  }

  @Test
  @DisplayName("injectBody with no body items returns the original body untouched")
  void injectBody_noItems_returnsSameInstance() {
    Map<String, Object> original = new LinkedHashMap<>(Map.of("amount", 100));

    assertThat(injector.injectBody(original, List.of())).isSameAs(original);
  }

  @Test
  @DisplayName("injectBody rejects a null body")
  void injectBody_nullBody_throws() {
    assertThatThrownBy(() -> injector.injectBody(null, List.of(body("token", "B"))))
        .isInstanceOf(InvalidParameterException.class);
  }

  @Test
  @DisplayName("injectBody rejects a non-object body (array)")
  void injectBody_arrayBody_throws() {
    assertThatThrownBy(() -> injector.injectBody(List.of(1, 2), List.of(body("token", "B"))))
        .isInstanceOf(InvalidParameterException.class);
  }

  @Test
  @DisplayName("injectBody rejects a path whose intermediate segment is not an object")
  void injectBody_intermediateNotObject_throws() {
    Map<String, Object> original = new LinkedHashMap<>(Map.of("meta", "text"));

    assertThatThrownBy(() -> injector.injectBody(original, List.of(body("meta.token", "B"))))
        .isInstanceOf(InvalidParameterException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Authorization", "authorization", "Cookie", "HOST", "Content-Length", "Transfer-Encoding"})
  @DisplayName("validateTarget rejects protected header names case-insensitively")
  void validateTarget_protectedHeader_throws(String name) {
    assertThatThrownBy(() -> ParameterInjector.validateTarget(ParameterInjectLocation.HEADER, name))
        .isInstanceOf(InvalidParameterException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Bad Header", "X:Y", "X\r\nInjected"})
  @DisplayName("validateTarget rejects malformed header names (header injection)")
  void validateTarget_malformedHeader_throws(String name) {
    assertThatThrownBy(() -> ParameterInjector.validateTarget(ParameterInjectLocation.HEADER, name))
        .isInstanceOf(InvalidParameterException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"meta..token", ".token", "token.", "a b", "$.token", "a[0]"})
  @DisplayName("validateTarget rejects malformed body paths")
  void validateTarget_malformedBodyPath_throws(String path) {
    assertThatThrownBy(() -> ParameterInjector.validateTarget(ParameterInjectLocation.BODY, path))
        .isInstanceOf(InvalidParameterException.class);
  }

  @Test
  @DisplayName("validateTarget accepts valid header, query and body targets")
  void validateTarget_validTargets_pass() {
    assertThatCode(() -> {
      ParameterInjector.validateTarget(ParameterInjectLocation.HEADER, "X-Partner-Id");
      ParameterInjector.validateTarget(ParameterInjectLocation.QUERY_PARAM, "partner_id");
      ParameterInjector.validateTarget(ParameterInjectLocation.BODY, "meta.partnerId");
    }).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"v\r\nX-Evil: 1", "v\nx", "v\u0000x", "v\u007fx"})
  @DisplayName("validateValue rejects control characters in HEADER values (response splitting)")
  void validateValue_headerWithControlChars_throws(String value) {
    assertThatThrownBy(() -> ParameterInjector.validateValue(ParameterInjectLocation.HEADER, value))
        .isInstanceOf(InvalidParameterException.class);
  }

  @Test
  @DisplayName("validateValue accepts tabs and printable text in HEADER values, and anything in BODY/QUERY values")
  void validateValue_validValues_pass() {
    assertThatCode(() -> {
      ParameterInjector.validateValue(ParameterInjectLocation.HEADER, "Bearer abc\t123 ~!");
      ParameterInjector.validateValue(ParameterInjectLocation.BODY, "line1\nline2");
      ParameterInjector.validateValue(ParameterInjectLocation.QUERY_PARAM, "a&b=c");
    }).doesNotThrowAnyException();
  }
}
