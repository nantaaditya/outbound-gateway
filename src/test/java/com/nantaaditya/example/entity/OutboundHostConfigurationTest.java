package com.nantaaditya.example.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.model.request.admin.UpdateHostConfigRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboundHostConfigurationTest {

  private static final UpdateHostConfigRequest UPDATE_REQUEST = new UpdateHostConfigRequest(
      3000, 100, 200, 5000, true, false, null, 0, null, null);

  private OutboundHostConfiguration original() {
    return OutboundHostConfiguration.builder()
        .id(1L)
        .baseUrl("https://api.example.com")
        .connectTimeOutMs(1000)
        .maxConnections(10)
        .pendingAcquireMaxCount(20)
        .pendingAcquireTimeoutMs(500)
        .disableSslVerification(false)
        .useProxy(false)
        .build();
  }

  @Test
  @DisplayName("updated returns a new instance — not the same reference as original")
  void updated_returnsNewInstance_notSameReference() {
    OutboundHostConfiguration entity = original();

    OutboundHostConfiguration result = entity.updated(UPDATE_REQUEST, null, null);

    assertThat(result).isNotSameAs(entity);
  }

  @Test
  @DisplayName("updated does not mutate original — all original fields remain unchanged")
  void updated_doesNotMutateOriginal_originalFieldsUnchanged() {
    OutboundHostConfiguration entity = original();

    entity.updated(UPDATE_REQUEST, null, null);

    assertThat(entity.getConnectTimeOutMs()).isEqualTo(1000);
    assertThat(entity.getMaxConnections()).isEqualTo(10);
    assertThat(entity.getPendingAcquireMaxCount()).isEqualTo(20);
    assertThat(entity.getPendingAcquireTimeoutMs()).isEqualTo(500);
    assertThat(entity.isDisableSslVerification()).isFalse();
    assertThat(entity.isUseProxy()).isFalse();
  }

  @Test
  @DisplayName("updated result carries new values from request")
  void updated_resultHasNewValues() {
    OutboundHostConfiguration entity = original();

    OutboundHostConfiguration result = entity.updated(UPDATE_REQUEST, null, null);

    assertThat(result.getConnectTimeOutMs()).isEqualTo(3000);
    assertThat(result.getMaxConnections()).isEqualTo(100);
    assertThat(result.getPendingAcquireMaxCount()).isEqualTo(200);
    assertThat(result.getPendingAcquireTimeoutMs()).isEqualTo(5000);
    assertThat(result.isDisableSslVerification()).isTrue();
    assertThat(result.isUseProxy()).isFalse();
  }

  @Test
  @DisplayName("updated preserves id and baseUrl from original")
  void updated_preservesIdAndBaseUrl() {
    OutboundHostConfiguration entity = original();

    OutboundHostConfiguration result = entity.updated(UPDATE_REQUEST, null, null);

    assertThat(result.getId()).isEqualTo(1L);
    assertThat(result.getBaseUrl()).isEqualTo("https://api.example.com");
  }

  @Test
  @DisplayName("updated carries encrypted proxy credentials into result")
  void updated_proxyCredentials_carriedIntoResult() {
    OutboundHostConfiguration entity = original();
    byte[] encUser = "user".getBytes();
    byte[] encPass = "pass".getBytes();

    OutboundHostConfiguration result = entity.updated(UPDATE_REQUEST, encUser, encPass);

    assertThat(result.getProxyUsername()).isEqualTo(encUser);
    assertThat(result.getProxyPassword()).isEqualTo(encPass);
    assertThat(entity.getProxyUsername()).isNull();
    assertThat(entity.getProxyPassword()).isNull();
  }
}
