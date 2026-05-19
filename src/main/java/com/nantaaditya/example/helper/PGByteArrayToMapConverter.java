package com.nantaaditya.example.helper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nantaaditya.example.model.logger.AppLogMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

@Log4j2
@ReadingConverter
public class PGByteArrayToMapConverter implements Converter<byte[], Map<String, Object>> {

  private final JsonHelper jsonHelper;

  public PGByteArrayToMapConverter(JsonHelper jsonHelper) {
    this.jsonHelper = jsonHelper;
  }

  @Override
  public Map<String, Object> convert(byte[] source) {
    if (source == null) return new HashMap<>();

    try {
      String jsonString = new String(source, StandardCharsets.UTF_8);
      return jsonHelper.fromJson(jsonString, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      log.error(AppLogMessage.message("#H2 - error parsing bytea to map database column").error(e));
    }
    return null;
  }

}
