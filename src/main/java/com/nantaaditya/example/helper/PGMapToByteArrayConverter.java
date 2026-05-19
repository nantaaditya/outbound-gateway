package com.nantaaditya.example.helper;

import com.nantaaditya.example.model.logger.AppLogMessage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.util.CollectionUtils;

@Log4j2
@WritingConverter
public class PGMapToByteArrayConverter implements Converter<Map<String, Object>, byte[]> {

  private final JsonHelper jsonHelper;

  public PGMapToByteArrayConverter(JsonHelper jsonHelper) {
    this.jsonHelper = jsonHelper;
  }

  @Override
  public byte[] convert(Map<String, Object> source) {
    if (CollectionUtils.isEmpty(source)) return null;

    try {
      String jsonString = jsonHelper.toJsonString(source);
      return jsonString.getBytes(StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.error(AppLogMessage.message("#H2 - error parsing map to bytea database column").error(e));
    }
    return null;
  }

}
