package com.nantaaditya.example.helper;

import com.nantaaditya.example.model.logger.AppLogMessage;
import io.r2dbc.postgresql.codec.Json;
import java.util.Map;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.util.CollectionUtils;

@Log4j2
@WritingConverter
public class PGMapToJsonConverter implements Converter<Map<String, Object>, Json> {

  private final JsonHelper jsonHelper;

  public PGMapToJsonConverter(JsonHelper jsonHelper) {
    this.jsonHelper = jsonHelper;
  }

  @Override
  public Json convert(Map<String, Object> source) {
    if (CollectionUtils.isEmpty(source)) return null;

    try {
      String jsonString = jsonHelper.toJsonString(source);
      return Json.of(jsonString);
    } catch (Exception e) {
      log.error(AppLogMessage.message("#POSTGRES - error parsing map to jsonb database column").error(e));
    }
    return null;
  }

}
