package com.nantaaditya.example.model.constant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeatureConstantTest {

  @Test
  @DisplayName("the outbound history purge endpoint is registered as an observed feature")
  void find_outboundHistoryPurge_isRegistered() {
    assertThat(FeatureConstant.find("DELETE", "/internal-api/outbound_history"))
        .contains(FeatureConstant.REMOVE_OBSOLETE_OUTBOUND_HISTORY);
  }

  @Test
  @DisplayName("the existing event log purge endpoint stays registered")
  void find_eventLogPurge_isRegistered() {
    assertThat(FeatureConstant.find("DELETE", "/internal-api/event_log"))
        .contains(FeatureConstant.REMOVE_OBSOLETE_EVENT_LOG);
  }
}
