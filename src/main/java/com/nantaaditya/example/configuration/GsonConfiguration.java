package com.nantaaditya.example.configuration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.LongSerializationPolicy;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GsonConfiguration {

  private static final JsonSerializer<Double> doubleJsonSerializer = new JsonSerializer<Double>() {
    @Override
    public JsonElement serialize(final Double src, final Type typeOfSrc, final JsonSerializationContext context) {
      BigDecimal value = BigDecimal.valueOf(src);
      return new JsonPrimitive(value);
    }
  };
  private static final JsonSerializer<Float> floatJsonSerializer = new JsonSerializer<Float>() {
    @Override
    public JsonElement serialize(final Float src, final Type typeOfSrc, final JsonSerializationContext context) {
      BigDecimal value = BigDecimal.valueOf(src);
      return new JsonPrimitive(value);
    }
  };
  private static final JsonSerializer<LocalDateTime> localDateTimeJsonSerializer = new JsonSerializer<LocalDateTime>() {
    @Override
    public JsonElement serialize(LocalDateTime src, Type typeOfSrc,
        JsonSerializationContext context) {
      return new JsonPrimitive(src.format(DateTimeFormatter.ISO_DATE_TIME));
    }
  };
  private static final JsonDeserializer<LocalDateTime> localDateTimeJsonDeserializer = new JsonDeserializer<LocalDateTime>() {
    @Override
    public LocalDateTime deserialize(JsonElement json, Type typeOfT,
        JsonDeserializationContext context) throws JsonParseException {
      return LocalDateTime.parse(json.getAsString(), DateTimeFormatter.ISO_DATE_TIME);
    }
  };

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
