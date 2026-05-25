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
        "code", "name", "desc", "post", "http://host", "/v1", true, 24);

    OutboundSystem system = OutboundSystem.create(request);

    assertThat(system.getHttpMethod()).isEqualTo("POST");
  }

  @Test
  @DisplayName("updated normalizes httpMethod to uppercase before storing")
  void updated_lowercaseHttpMethod_normalizesToUpperCase() {
    OutboundSystem existing = OutboundSystem.builder()
        .id(1L)
        .code("code")
        .httpMethod("GET")
        .createdBy("test")
        .version(1)
        .build();

    UpdateOutboundSystemRequest request = new UpdateOutboundSystemRequest(
        "name", "desc", "delete", "http://host", "/v1", true, 24);

    OutboundSystem updated = existing.updated(request);

    assertThat(updated.getHttpMethod()).isEqualTo("DELETE");
  }
}
