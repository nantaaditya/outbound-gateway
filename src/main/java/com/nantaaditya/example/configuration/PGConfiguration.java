package com.nantaaditya.example.configuration;

import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.helper.PGByteArrayToMapConverter;
import com.nantaaditya.example.helper.PGJsonToMapConverter;
import com.nantaaditya.example.helper.PGMapToByteArrayConverter;
import com.nantaaditya.example.helper.PGMapToJsonConverter;
import io.r2dbc.spi.ConnectionFactory;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.DialectResolver;

@Configuration
public class PGConfiguration {

  @Value("${cron.internal.integration-test:false}")
  private boolean isIntegrationTest;

  @Autowired
  private JsonHelper jsonHelper;

  @Bean
  public R2dbcCustomConversions customConversions(ConnectionFactory connectionFactory) {
    List<Converter<?, ?>> converters = new ArrayList<>();
    if (!isIntegrationTest) {
      converters.add(new PGJsonToMapConverter(jsonHelper));
      converters.add(new PGMapToJsonConverter(jsonHelper));
    } else {
      converters.add(new PGByteArrayToMapConverter(jsonHelper));
      converters.add(new PGMapToByteArrayConverter(jsonHelper));
    }
    return R2dbcCustomConversions.of(DialectResolver.getDialect(connectionFactory), converters);
  }
}
