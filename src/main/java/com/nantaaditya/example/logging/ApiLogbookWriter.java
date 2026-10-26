package com.nantaaditya.example.logging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.logger.JsonLogHttpRequest;
import com.nantaaditya.example.model.logger.JsonLogHttpResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import lombok.extern.log4j.Log4j2;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.HttpLogWriter;
import org.zalando.logbook.Precorrelation;

@Log4j2
public class ApiLogbookWriter implements HttpLogWriter {

  private final ObjectMapper mapper;

  private static final String TYPE = "type";
  private static final String CORRELATION = "correlation";
  private static final String PROTOCOL = "protocol";

  public ApiLogbookWriter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void write(Precorrelation precorrelation, String request) throws IOException {
    try {
      Map<String, Object> content = mapper.readValue(request, LinkedHashMap.class);
      String method = (String) content.get("method");
      String url = (String) content.get("uri");
      MultiValueMap<String, String> headers = getHeaders(content);
      Object body = content.get("body");
      JsonLogHttpRequest jsonLogHttpRequest = new JsonLogHttpRequest(
          method,
          url,
          headers,
          body
      );

      Map<String, Object> additionalData = new LinkedHashMap<>();
      additionalData.put(TYPE, "request");
      additionalData.put(CORRELATION, precorrelation.getId());
      additionalData.put(PROTOCOL, content.get(PROTOCOL));

      log.info(AppLogMessage.message("#API: incoming request")
          .httpRequest(jsonLogHttpRequest)
          .additionalData(additionalData));
    } catch (Exception e) {
      log.error(AppLogMessage.message("#API: incoming request").error(e));
    }
  }

  @Override
  public void write(Correlation correlation, String response) throws IOException {
    try {
      Map<String, Object> content = mapper.readValue(response, LinkedHashMap.class);
      int status = (int) content.get("status");
      int duration =  (int) content.get("duration");
      MultiValueMap<String, String> headers = getHeaders(content);
      Object body = content.get("body");
      JsonLogHttpResponse jsonLogHttpResponse = new JsonLogHttpResponse(
          null,
          null,
          String.valueOf(status),
          String.format("%s ms", duration),
          headers,
          body
      );

      Map<String, Object> additionalData = new LinkedHashMap<>();
      additionalData.put(TYPE, "response");
      additionalData.put(CORRELATION, correlation.getId());
      additionalData.put(PROTOCOL, content.get(PROTOCOL));

      log.info(AppLogMessage.message("#API: outgoing response")
          .httpResponse(jsonLogHttpResponse)
          .additionalData(additionalData));
    } catch (Exception e) {
      log.error(AppLogMessage.message("#API: outgoing response").error(e));
    }
  }

  private MultiValueMap<String, String> getHeaders(Map<String, Object> content) {
    Map<String, List<String>> headers = mapper.convertValue(content.get("headers"), new TypeReference<Map<String, List<String>>>() {});
    MultiValueMap<String, String> multiValueMap = new LinkedMultiValueMap<>();
    for (Entry<String, List<String>> entry : headers.entrySet()) {
      multiValueMap.put(entry.getKey(), entry.getValue());
    }
    return multiValueMap;
  }
}