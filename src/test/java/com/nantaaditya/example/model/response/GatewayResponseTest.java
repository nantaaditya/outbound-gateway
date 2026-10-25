package com.nantaaditya.example.model.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GatewayResponseTest {

  @Test
  @DisplayName("asReplay keeps the content and only flags the response as served from a previous execution")
  void asReplay_flagsFromCacheAndKeepsContent() {
    GatewayResponse original = GatewayResponse.mapped(
        201, Map.of("X-Trace", List.of("abc")), "{\"id\":1}", 200, "{\"mapped\":true}");

    GatewayResponse replay = original.asReplay();

    assertThat(replay.fromCache()).isTrue();
    assertThat(replay.status()).isEqualTo(201);
    assertThat(replay.headers()).isEqualTo(original.headers());
    assertThat(replay.body()).isEqualTo("{\"id\":1}");
    assertThat(replay.convertedStatus()).isEqualTo(200);
    assertThat(replay.convertedResponse()).isEqualTo("{\"mapped\":true}");
  }

  @Test
  @DisplayName("asReplay does not modify the original and preserves the mapping-fallback flag")
  void asReplay_leavesOriginalUntouched() {
    GatewayResponse original = GatewayResponse.mappedWithFallback(200, Map.of(), "raw", 200);

    GatewayResponse replay = original.asReplay();

    assertThat(original.fromCache()).isFalse();
    assertThat(replay.mappingFallback()).isTrue();
  }
}
