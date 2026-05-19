package com.nantaaditya.example.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.model.constant.ConverterType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboundResponseMapperConfigurationTest {

  @Test
  @DisplayName("builder creates entity with all fields set")
  void builder_allFields_entityCreated() {
    OutboundResponseMapperConfiguration config = OutboundResponseMapperConfiguration.builder()
        .outboundSystemId(1L)
        .converterType(ConverterType.JSLT)
        .mappingScript(".name")
        .mappingStatus(Map.of("404", 400, "500", 503))
        .active(true)
        .build();

    assertThat(config.getOutboundSystemId()).isEqualTo(1L);
    assertThat(config.getConverterType()).isEqualTo(ConverterType.JSLT);
    assertThat(config.getMappingScript()).isEqualTo(".name");
    assertThat(config.getMappingStatus()).containsEntry("404", 400);
    assertThat(config.isActive()).isTrue();
  }

  @Test
  @DisplayName("isNew returns true when id is null")
  void isNew_nullId_returnsTrue() {
    OutboundResponseMapperConfiguration config = OutboundResponseMapperConfiguration.builder()
        .outboundSystemId(1L)
        .converterType(ConverterType.NONE)
        .build();

    assertThat(config.isNew()).isTrue();
  }

  @Test
  @DisplayName("isNew returns false when id is set")
  void isNew_idSet_returnsFalse() {
    OutboundResponseMapperConfiguration config = OutboundResponseMapperConfiguration.builder()
        .id(10L)
        .outboundSystemId(1L)
        .converterType(ConverterType.NONE)
        .build();

    assertThat(config.isNew()).isFalse();
  }

  @Test
  @DisplayName("isPassThrough returns true when converter type is NONE")
  void isPassThrough_noneType_returnsTrue() {
    OutboundResponseMapperConfiguration config = OutboundResponseMapperConfiguration.builder()
        .converterType(ConverterType.NONE)
        .build();

    assertThat(config.isPassThrough()).isTrue();
  }

  @Test
  @DisplayName("isPassThrough returns false when converter type is JSLT")
  void isPassThrough_jsltType_returnsFalse() {
    OutboundResponseMapperConfiguration config = OutboundResponseMapperConfiguration.builder()
        .converterType(ConverterType.JSLT)
        .build();

    assertThat(config.isPassThrough()).isFalse();
  }

  @Test
  @DisplayName("getMappedStatus returns original status when no mapping configured")
  void getMappedStatus_noMapping_returnsOriginal() {
    OutboundResponseMapperConfiguration config = OutboundResponseMapperConfiguration.builder()
        .converterType(ConverterType.NONE)
        .mappingStatus(null)
        .build();

    assertThat(config.getMappedStatus(200)).isEqualTo(200);
    assertThat(config.getMappedStatus(404)).isEqualTo(404);
  }

  @Test
  @DisplayName("getMappedStatus returns mapped status when mapping configured")
  void getMappedStatus_withMapping_returnsMappedStatus() {
    OutboundResponseMapperConfiguration config = OutboundResponseMapperConfiguration.builder()
        .converterType(ConverterType.JSLT)
        .mappingStatus(Map.of("404", 400, "500", 503))
        .build();

    assertThat(config.getMappedStatus(404)).isEqualTo(400);
    assertThat(config.getMappedStatus(500)).isEqualTo(503);
    assertThat(config.getMappedStatus(200)).isEqualTo(200);
  }
}
