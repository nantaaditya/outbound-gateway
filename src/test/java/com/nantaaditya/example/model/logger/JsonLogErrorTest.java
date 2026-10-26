package com.nantaaditya.example.model.logger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The error part of a JSON log line. It is written by hand, so the one property that matters is that whatever the
 * message contains, the line stays valid JSON that decodes back to the same message.
 */
class JsonLogErrorTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("create keeps the message and the stack trace without its first frame")
  void create_keepsMessageAndSkipsFirstFrame() {
    RuntimeException error = new RuntimeException("boom");

    JsonLogError logError = JsonLogError.create(error);

    assertThat(logError.errorMessage()).isEqualTo("boom");
    assertThat(logError.errorStackTrace()).hasSize(error.getStackTrace().length - 1);
    assertThat(logError.errorStackTrace()[0]).isEqualTo(error.getStackTrace()[1].toString());
  }

  @Test
  @DisplayName("toString renders message and stack trace as a JSON object")
  void toString_rendersJsonObject() {
    JsonLogError logError = new JsonLogError("boom", new String[] {"a.B.c(B.java:1)", "d.E.f(E.java:2)"});

    assertThat(logError.toString())
        .isEqualTo("{\"error_message\":\"boom\",\"error_stacktrace\":[\"a.B.c(B.java:1)\",\"d.E.f(E.java:2)\"]}");
  }

  @Test
  @DisplayName("a null message and a null or empty stack trace are rendered as null and []")
  void toString_nullAndEmpty() {
    assertThat(new JsonLogError(null, null).toString())
        .isEqualTo("{\"error_message\":null,\"error_stacktrace\":null}");
    assertThat(new JsonLogError("m", new String[0]).toString())
        .isEqualTo("{\"error_message\":\"m\",\"error_stacktrace\":[]}");
  }

  @Test
  @DisplayName("quotes, backslashes and line breaks are escaped")
  void toString_escapesCommonCharacters() {
    JsonLogError logError = new JsonLogError("say \"hi\" C:\\temp\nline2\r\tend", new String[0]);

    assertThat(logError.toString())
        .isEqualTo("{\"error_message\":\"say \\\"hi\\\" C:\\\\temp\\nline2\\r\\tend\",\"error_stacktrace\":[]}");
  }

  @Test
  @DisplayName("any control character keeps the line valid JSON and decodes back to the original message")
  void toString_controlCharacters_stayValidJson() throws Exception {
    String message = "bell\u0007 backspace\b formfeed\f nul\u0000 unit\u001f end \"q\" \\ \n";
    JsonLogError logError = new JsonLogError(message, new String[] {"frame\u0001"});

    JsonNode parsed = objectMapper.readTree(logError.toString());

    assertThat(parsed.get("error_message").asText()).isEqualTo(message);
    assertThat(parsed.get("error_stacktrace").get(0).asText()).isEqualTo("frame\u0001");
  }

  @Test
  @DisplayName("equality compares the message and the stack trace contents")
  void equalsAndHashCode() {
    JsonLogError first = new JsonLogError("m", new String[] {"a", "b"});
    JsonLogError same = new JsonLogError("m", new String[] {"a", "b"});

    assertThat(first).isEqualTo(first).isEqualTo(same).hasSameHashCodeAs(same);
    assertThat(first).isNotEqualTo(new JsonLogError("other", new String[] {"a", "b"}));
    assertThat(first).isNotEqualTo(new JsonLogError("m", new String[] {"a"}));
    assertThat(first).isNotEqualTo(null);
    assertThat(first).isNotEqualTo("m");
  }
}
