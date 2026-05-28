package com.nantaaditya.example.configuration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.LongSerializationPolicy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GsonConfiguration {

  private static final JsonSerializer<Double> doubleJsonSerializer = (src, typeOfSrc, context) -> {
    BigDecimal value = BigDecimal.valueOf(src);
    return new JsonPrimitive(value);
  };
  private static final JsonSerializer<Float> floatJsonSerializer = (src, typeOfSrc, context) -> {
    BigDecimal value = BigDecimal.valueOf(src);
    return new JsonPrimitive(value);
  };
  private static final JsonSerializer<LocalDateTime> localDateTimeJsonSerializer = (src, typeOfSrc, context) -> new JsonPrimitive(src.format(DateTimeFormatter.ISO_DATE_TIME));
  private static final JsonDeserializer<LocalDateTime> localDateTimeJsonDeserializer = (json, typeOfT, context) -> LocalDateTime.parse(json.getAsString(), DateTimeFormatter.ISO_DATE_TIME);

  @Bean
  public Gson gson() {
    GsonBuilder gsonBuilder = new GsonBuilder();
    gsonBuilder.setLongSerializationPolicy(LongSerializationPolicy.STRING);
    gsonBuilder.disableHtmlEscaping();
    gsonBuilder.registerTypeAdapter(Double.class, doubleJsonSerializer);
    gsonBuilder.registerTypeAdapter(Float.class, floatJsonSerializer);
    gsonBuilder.registerTypeAdapter(LocalDateTime.class, localDateTimeJsonSerializer);
    gsonBuilder.registerTypeAdapter(LocalDateTime.class, localDateTimeJsonDeserializer);
    return gsonBuilder.create();
  }
}
