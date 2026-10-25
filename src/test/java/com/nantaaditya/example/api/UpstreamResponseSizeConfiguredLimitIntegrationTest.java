package com.nantaaditya.example.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The limit comes from {@code apps.gateway.max-response-bytes} (env MAX_RESPONSE_BYTES). */
@TestPropertySource(properties = "apps.gateway.max-response-bytes=2000")
class UpstreamResponseSizeConfiguredLimitIntegrationTest extends AbstractUpstreamResponseSizeTest {

  @Test
  @DisplayName("a response under the configured limit is passed through")
  void execute_responseUnderLimit_passesThrough() throws Exception {
    JsonNode response = callUpstreamReturning(1000);

    assertThat(response.path("data").path("status").asInt()).isEqualTo(200);
    assertThat(response.path("data").path("body").path("payload").asText()).hasSize(1000);
  }

  @Test
  @DisplayName("a response over the configured limit is a 502 that says the upstream answer was too large")
  void execute_responseOverLimit_is502WithClearReason() throws Exception {
    JsonNode response = callUpstreamReturning(5000);

    assertThat(response.path("data").path("status").asInt()).isEqualTo(502);
    assertThat(response.path("data").toString()).contains("upstream response too large");
  }
}
