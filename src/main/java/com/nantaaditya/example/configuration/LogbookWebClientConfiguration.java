package com.nantaaditya.example.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.nantaaditya.example.logging.ApiLogbookFormatter;
import com.nantaaditya.example.logging.ApiLogbookWriter;
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.properties.LogProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.LogbookCreator;
import org.zalando.logbook.core.DefaultSink;
import org.zalando.logbook.core.HeaderFilters;

@Configuration
@RequiredArgsConstructor
public class LogbookWebClientConfiguration {

  @Bean
  @Primary
  public Logbook logbook(ObjectMapper objectMapper, Gson gson, LogProperties logProperties) {
    return LogbookCreator.builder()
        .correlationId(this::composeCorrelationId)
        .headerFilter(HeaderFilters.replaceHeaders(logProperties.getSensitiveFields(), "*"))
        .sink(new DefaultSink(
            new ApiLogbookFormatter(objectMapper, gson, logProperties),
            new ApiLogbookWriter(objectMapper)
        ))
        .build();
  }

  private String composeCorrelationId(HttpRequest httpRequest) {
    String requestId = httpRequest.getHeaders().getFirst(HeaderConstant.REQUEST_ID.getHeader());
    MDC.put(ConstantName.REQUEST_ID_MDC, requestId);
    return requestId;
  }
}
