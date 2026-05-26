package com.nantaaditya.example.helper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nantaaditya.example.model.logger.AppLogMessage;
import io.r2dbc.postgresql.codec.Json;
import java.util.Map;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.util.StringUtils;

@Log4j2
@ReadingConverter
public class PGJsonToIntegerMapConverter implements Converter<Json, Map<String, Integer>> {

  private final JsonHelper jsonHelper;

  public PGJsonToIntegerMapConverter(JsonHelper jsonHelper) {
    this.jsonHelper = jsonHelper;
  }

  @Override
  public Map<String, Integer> convert(Json source) {
    if (source == null || !StringUtils.hasLength(source.asString())) return null;

    try {
      return jsonHelper.fromJson(source.asString(), new TypeReference<Map<String, Integer>>() {});
    } catch (Exception e) {
      log.error(AppLogMessage.message("#POSTGRES - error parsing jsonb to integer map").error(e));
    }
    return null;
  }
}
