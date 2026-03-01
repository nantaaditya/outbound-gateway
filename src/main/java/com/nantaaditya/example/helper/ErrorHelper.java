package com.nantaaditya.example.helper;

import io.micrometer.core.instrument.util.StringEscapeUtils;

public class ErrorHelper {

  private static final String STACKTRACE_FORMAT = "root cause: %s at %s:%s";
  private static final String EMPTY_STACKTRACE_FORMAT = "root cause: %s (no stack trace)";

  private ErrorHelper() {}

  public static String getRootCause(Throwable throwable) {
    Throwable root = throwable;
    while (root.getCause() != null) {
      root = root.getCause();
    }

    String message = root.getMessage();
    StackTraceElement[] stackTrace = root.getStackTrace();
    if (stackTrace.length > 0) {
      StackTraceElement origin = stackTrace[0];
      return StringEscapeUtils.escapeJson(String.format(STACKTRACE_FORMAT,
          message,
          origin.getClassName() + "." + origin.getMethodName(),
          origin.getLineNumber()
      ));
    } else {
      return StringEscapeUtils.escapeJson(String.format(EMPTY_STACKTRACE_FORMAT, message));
    }
  }
}
