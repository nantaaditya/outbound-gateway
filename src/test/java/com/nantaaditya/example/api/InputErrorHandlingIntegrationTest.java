package com.nantaaditya.example.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nantaaditya.example.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * A request the client got wrong (missing or malformed input) is a client error. It used to fall into the generic
 * handler and come back as 500, which monitoring counts as a server fault and 5xx-retrying clients repeat for ever.
 */
class InputErrorHandlingIntegrationTest extends BaseIntegrationTest {

  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("a missing required query parameter is a 400 that names the parameter")
  void missingRequiredParameter_is400() throws Exception {
    JsonNode body = deleteExpecting400("/internal-api/outbound_history");

    assertThat(body.path("response").path("code").asText()).isEqualTo("900");
    assertThat(body.toString()).contains("days");
  }

  @Test
  @DisplayName("a query parameter of the wrong type is a 400 that names the parameter")
  void wrongTypeParameter_is400() throws Exception {
    JsonNode body = deleteExpecting400("/internal-api/outbound_history?days=abc");

    assertThat(body.path("response").path("code").asText()).isEqualTo("900");
    assertThat(body.toString()).contains("days");
  }

  @Test
  @DisplayName("a malformed JSON body is a 400")
  void malformedJsonBody_is400() throws Exception {
    byte[] response = webTestClient.post()
        .uri("/api/gateway/execute/anything")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"queryParams\": ")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody().returnResult().getResponseBody();

    assertThat(objectMapper.readTree(response).path("response").path("code").asText()).isEqualTo("900");
  }

  private JsonNode deleteExpecting400(String uri) throws Exception {
    byte[] response = webTestClient.delete()
        .uri(uri)
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody().returnResult().getResponseBody();
    return objectMapper.readTree(response);
  }
}
