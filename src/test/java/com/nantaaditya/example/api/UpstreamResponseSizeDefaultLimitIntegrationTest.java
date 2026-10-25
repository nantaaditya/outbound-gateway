package com.nantaaditya.example.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** With no configuration the gateway must pass a large (but reasonable) upstream answer through unchanged. */
class UpstreamResponseSizeDefaultLimitIntegrationTest extends AbstractUpstreamResponseSizeTest {

  @Test
  @DisplayName("a 300 KB upstream response (above Spring's 256 KB default) is passed through, not turned into a 503")
  void execute_largeResponse_passesThrough() throws Exception {
    JsonNode response = callUpstreamReturning(300 * 1024);

    assertThat(response.path("data").path("status").asInt()).isEqualTo(200);
    assertThat(response.path("data").path("body").path("payload").asText()).hasSize(300 * 1024);
  }
}
