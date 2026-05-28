package com.nantaaditya.example.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nantaaditya.example.helper.JsonHelper;
import io.r2dbc.postgresql.codec.Json;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table(name = "outbound_header_configurations")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@SuppressWarnings("java:S1068")
public class OutboundHeaderConfiguration extends BaseEntity<Long> {
  private long outboundSystemId;
  private Map<String, Object> headers;

  public Map<String, List<String>> getHeaders(JsonHelper jsonHelper) {
    return jsonHelper.convert(headers, new TypeReference<Map<String, List<String>>>() {});
  }

  public void setHeaders(JsonHelper jsonHelper, Object headers) {
    if (headers == null) return;

    if (headers instanceof byte[] b) {
      this.headers = jsonHelper.convert(b, new TypeReference<Map<String, Object>>() {});
    } else if (headers instanceof Json j) {
      this.headers = jsonHelper.fromJson(j, new TypeReference<Map<String, Object>>() {});
    } else if (headers instanceof Map m) {
      this.headers = jsonHelper.convert(m, new TypeReference<Map<String, Object>>() {});
    } else {
      throw new IllegalArgumentException(OBJECT_NOT_VALID_ERROR);
    }
  }
}
