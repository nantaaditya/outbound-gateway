package com.nantaaditya.example.helper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class JsonHelper {

  private final ObjectMapper objectMapper;

  @SneakyThrows
  public String toJsonString(Object object) {
    if (object == null) return null;
    return objectMapper.writeValueAsString(object);
  }

  public Json toJson(Object object) {
    if (object == null) return null;
    return Json.of(toJsonString(object));
  }

  @SneakyThrows
  public <T> T fromJson(String string, TypeReference<T> typeReference) {
    if (!StringUtils.hasLength(string)) return null;
    return objectMapper.readValue(string, typeReference);
  }

  public <T> T fromJson(Json json, TypeReference<T> typeReference) {
    if (json == null || !StringUtils.hasLength(json.asString())) return null;
    return fromJson(json.asString(), typeReference);
  }

  public <S, T> T convert(S source, TypeReference<T> typeReference) {
    if (source == null) return null;
    return objectMapper.convertValue(source, typeReference);
  }

  @SneakyThrows
  public <T> T convert(byte[] source, TypeReference<T> typeReference) {
    if (source == null) return null;
    return objectMapper.readValue(source, typeReference);
  }

  public Object parseJsonOrRaw(String raw) {
    if (!StringUtils.hasLength(raw)) return null;
    try {
      return objectMapper.readValue(raw, Object.class);
    } catch (Exception e) {
      return raw;
    }
  }
}
