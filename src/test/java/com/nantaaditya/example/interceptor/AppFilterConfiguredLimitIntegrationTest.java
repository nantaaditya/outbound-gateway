package com.nantaaditya.example.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The limit must come from the property documented for it ({@code apps.filter.max-body-bytes}, the one
 * {@code MAX_PAYLOAD_SIZE} feeds). A unit test cannot catch a mismatch between that key and the code, so this runs the
 * real context with a small limit.
 */
@TestPropertySource(properties = "apps.filter.max-body-bytes=200")
class AppFilterConfiguredLimitIntegrationTest extends BaseIntegrationTest {

  @Test
  @DisplayName("a body above the configured limit is rejected with 413")
  void execute_bodyAboveConfiguredLimit_returns413() {
    String json = "{\"padding\":\"" + "x".repeat(500) + "\"}";

    webTestClient.post()
        .uri("/api/gateway/execute/ghost")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(json)
        .exchange()
        .expectStatus().isEqualTo(413);
  }

  @Test
  @DisplayName("a body under the configured limit is not rejected by the filter")
  void execute_bodyUnderConfiguredLimit_isNotRejectedByFilter() {
    int status = webTestClient.post()
        .uri("/api/gateway/execute/ghost")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"a\":1}")
        .exchange()
        .returnResult(String.class)
        .getStatus()
        .value();

    assertThat(status).isNotEqualTo(413);
  }
}
