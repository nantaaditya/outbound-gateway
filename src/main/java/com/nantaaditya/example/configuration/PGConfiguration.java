package com.nantaaditya.example.configuration;

import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.helper.PGJsonToIntegerMapConverter;
import com.nantaaditya.example.helper.PGJsonToMapConverter;
import com.nantaaditya.example.helper.PGMapToJsonConverter;
import io.r2dbc.spi.ConnectionFactory;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.DialectResolver;

@Configuration
public class PGConfiguration {

  private final JsonHelper jsonHelper;

  public PGConfiguration(JsonHelper jsonHelper) {
    this.jsonHelper = jsonHelper;
  }

  @Bean
  public R2dbcCustomConversions customConversions(ConnectionFactory connectionFactory) {
    List<Converter<?, ?>> converters = List.of(
        new PGJsonToMapConverter(jsonHelper),
        new PGJsonToIntegerMapConverter(jsonHelper),
        new PGMapToJsonConverter(jsonHelper)
    );
    return R2dbcCustomConversions.of(DialectResolver.getDialect(connectionFactory), converters);
  }
}
