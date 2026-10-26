package com.nantaaditya.example.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.model.logger.AppLogMessage;
import io.r2dbc.postgresql.codec.Json;
import java.util.Map;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.util.StringUtils;

@Log4j2
@ReadingConverter
public class PGJsonToMapConverter implements Converter<Json, Map<String, Object>> {

  private final JsonHelper jsonHelper;

  public PGJsonToMapConverter(JsonHelper jsonHelper) {
    this.jsonHelper = jsonHelper;
  }

  @Override
  public Map<String, Object> convert(Json source) {
    if (source == null || !StringUtils.hasLength(source.asString())) return null;

    try {
      return jsonHelper.fromJson(source.asString(), new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      log.error(AppLogMessage.message("#POSTGRES - error parsing jsonb to map database column").error(e));
    }
    return null;
  }

}
