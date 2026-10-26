package com.nantaaditya.example.helper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The timestamps in API responses and log lines. Bad input yields null instead of breaking the response or the log. */
class DateTimeHelperTest {

  private static final long EPOCH_MILLIS = 1_700_000_000_123L;

  @Test
  @DisplayName("a zoned time is rendered in the ISO-8601 response format with milliseconds and offset")
  void getDateInFormat_zonedDateTime_isoFormat() {
    ZonedDateTime time = ZonedDateTime.of(2026, 10, 26, 8, 0, 0, 123_000_000, ZoneOffset.ofHours(7));

    assertThat(DateTimeHelper.getDateInFormat(time, DateTimeHelper.ISO_8601_GMT7_FORMAT))
        .isEqualTo("2026-10-26T08:00:00.123+07:00");
  }

  @Test
  @DisplayName("a null time or a blank pattern gives null")
  void getDateInFormat_zonedDateTime_nullOrBlank() {
    assertThat(DateTimeHelper.getDateInFormat(null, DateTimeHelper.ISO_8601_GMT7_FORMAT)).isNull();
    assertThat(DateTimeHelper.getDateInFormat(ZonedDateTime.now(), " ")).isNull();
  }

  @Test
  @DisplayName("a pattern that cannot be used gives null instead of an exception")
  void getDateInFormat_zonedDateTime_invalidPattern() {
    assertThat(DateTimeHelper.getDateInFormat(ZonedDateTime.now(), "invalid{pattern")).isNull();
  }

  @Test
  @DisplayName("epoch milliseconds are rendered with the log formatter in the system zone")
  void getDateInFormat_millis_logFormat() {
    String expected = ZonedDateTime.ofInstant(Instant.ofEpochMilli(EPOCH_MILLIS), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss:SSS"));

    assertThat(DateTimeHelper.getDateInFormat(EPOCH_MILLIS, DateTimeHelper.LOG_TIMESTAMP_FORMATTER))
        .isEqualTo(expected)
        .endsWith(":123");
  }

  @Test
  @DisplayName("zero or negative milliseconds and a missing formatter give null")
  void getDateInFormat_millis_invalidInput() {
    assertThat(DateTimeHelper.getDateInFormat(0, DateTimeHelper.LOG_TIMESTAMP_FORMATTER)).isNull();
    assertThat(DateTimeHelper.getDateInFormat(-5, DateTimeHelper.LOG_TIMESTAMP_FORMATTER)).isNull();
    assertThat(DateTimeHelper.getDateInFormat(EPOCH_MILLIS, null)).isNull();
  }

  @Test
  @DisplayName("a formatter that needs a zone the local time does not have gives null instead of an exception")
  void getDateInFormat_millis_unsupportedField() {
    assertThat(DateTimeHelper.getDateInFormat(EPOCH_MILLIS, DateTimeFormatter.ofPattern("yyyy VV"))).isNull();
  }
}
