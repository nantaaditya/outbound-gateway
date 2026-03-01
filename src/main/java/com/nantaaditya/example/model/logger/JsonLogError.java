package com.nantaaditya.example.model.logger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Arrays;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonLogError(
    @JsonProperty("error_message")
    String errorMessage,
    @JsonProperty("error_stacktrace")
    String [] errorStackTrace
) {

  public static JsonLogError create(Throwable throwable) {
    return new JsonLogError(throwable.getMessage(), getStackTrace(throwable));
  }

  private static String[] getStackTrace(Throwable throwable) {
    return Arrays.stream(throwable.getStackTrace())
        .skip(1)
        .map(StackTraceElement::toString)
        .toArray(String[]::new);
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    JsonLogError that = (JsonLogError) o;
    return Objects.equals(errorMessage, that.errorMessage)
        && Objects.deepEquals(errorStackTrace, that.errorStackTrace);
  }

  @Override
  public int hashCode() {
    return Objects.hash(errorMessage, Arrays.hashCode(errorStackTrace));
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("{");

    // error_message
    sb.append("\"error_message\":");
    appendJsonString(sb, errorMessage);
    sb.append(",");

    // error_stacktrace
    sb.append("\"error_stacktrace\":");
    appendJsonArray(sb, errorStackTrace);

    sb.append("}");
    return sb.toString();
  }

  private static void appendJsonArray(StringBuilder sb, String[] array) {
    if (array == null) {
      sb.append("null");
      return;
    }

    sb.append("[");
    for (int i = 0; i < array.length; i++) {
      appendJsonString(sb, array[i]);
      if (i < array.length - 1) {
        sb.append(",");
      }
    }
    sb.append("]");
  }

  private static void appendJsonString(StringBuilder sb, String value) {
    if (value == null) {
      sb.append("null");
      return;
    }

    sb.append("\"");
    for (char c : value.toCharArray()) {
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default: sb.append(c);
      }
    }
    sb.append("\"");
  }
}