package com.nantaaditya.example.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.model.request.admin.CreateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpdateOutboundSystemRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class OutboundSystemTest {

  @Test
  @DisplayName("getHttpMethodEnum with lowercase input returns correct HttpMethod without throwing")
  void getHttpMethodEnum_lowercaseHttpMethod_returnsHttpMethodPost() {
    OutboundSystem system = OutboundSystem.builder()
        .httpMethod("post")
        .build();

    assertThat(system.getHttpMethodEnum()).isEqualTo(HttpMethod.POST);
  }

  @Test
  @DisplayName("create normalizes httpMethod to uppercase before storing")
  void create_lowercaseHttpMethod_normalizesToUpperCase() {
    CreateOutboundSystemRequest request = new CreateOutboundSystemRequest(
        "code", "name", "desc", "post", 42L, "/v1", true, 24);

    OutboundSystem system = OutboundSystem.create(request);

    assertThat(system.getHttpMethod()).isEqualTo("POST");
  }

  @Test
  @DisplayName("create propagates hostConfigurationId from the request")
  void create_withHostConfigurationId_propagatesToEntity() {
    CreateOutboundSystemRequest request = new CreateOutboundSystemRequest(
        "code", "name", "desc", "post", 42L, "/v1", true, 24);

    OutboundSystem system = OutboundSystem.create(request);

    assertThat(system.getHostConfigurationId()).isEqualTo(42L);
  }

  @Test
  @DisplayName("updated normalizes httpMethod to uppercase before storing")
  void updated_lowercaseHttpMethod_normalizesToUpperCase() {
    OutboundSystem existing = OutboundSystem.builder()
        .id(1L)
        .code("code")
        .httpMethod("GET")
        .hostConfigurationId(1L)
        .createdBy("test")
        .version(1)
        .build();

    UpdateOutboundSystemRequest request = new UpdateOutboundSystemRequest(
        "name", "desc", "delete", 99L, "/v1", true, 24);

    OutboundSystem updated = existing.updated(request);

    assertThat(updated.getHttpMethod()).isEqualTo("DELETE");
  }

  @Test
  @DisplayName("updated replaces hostConfigurationId with the value from the request")
  void updated_withNewHostConfigurationId_replacesExisting() {
    OutboundSystem existing = OutboundSystem.builder()
        .id(1L)
        .code("code")
        .httpMethod("GET")
        .hostConfigurationId(1L)
        .createdBy("test")
        .version(1)
        .build();

    UpdateOutboundSystemRequest request = new UpdateOutboundSystemRequest(
        "name", "desc", "delete", 99L, "/v1", true, 24);

    OutboundSystem updated = existing.updated(request);

    assertThat(updated.getHostConfigurationId()).isEqualTo(99L);
  }

  @Test
  @DisplayName("getFullPath concatenates the caller-supplied baseUrl with apiPath")
  void getFullPath_withBaseUrl_concatenatesBaseUrlAndApiPath() {
    OutboundSystem system = OutboundSystem.builder()
        .apiPath("/v1/charge")
        .build();

    assertThat(system.getFullPath("https://api.example.com"))
        .isEqualTo("https://api.example.com/v1/charge");
  }
}
