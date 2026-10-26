package com.nantaaditya.example.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.nantaaditya.example.properties.LogProperties;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.zalando.logbook.ContentType;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.HttpLogFormatter;
import org.zalando.logbook.HttpMessage;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Precorrelation;

public class ApiLogbookFormatter implements HttpLogFormatter {

  private final ObjectMapper mapper;
  private final Gson gson;
  private final LogProperties logProperties;

  public ApiLogbookFormatter(ObjectMapper mapper, Gson gson, LogProperties logProperties) {
    this.mapper = mapper;
    this.gson = gson;
    this.logProperties = logProperties;
  }

  @Override
  public String format(Precorrelation precorrelation, HttpRequest request) throws IOException {
    return mapper.writeValueAsString(request(precorrelation, request));
  }

  @Override
  public String format(Correlation correlation, HttpResponse response) throws IOException {
    return mapper.writeValueAsString(response(correlation, response));
  }

  private Map<String, Object> request(Precorrelation precorrelation, HttpRequest request)
    throws IOException {
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("origin", request.getOrigin().name().toLowerCase(Locale.ROOT));
    content.put("type", "request");
    content.put("correlation", precorrelation.getId());
    content.put("protocol", request.getProtocolVersion());
    content.put("remote", request.getRemote());
    content.put("method", request.getMethod());
    content.put("uri", request.getRequestUri());
    content.put("host", request.getHost());
    content.put("path", request.getPath());
    content.put("scheme", request.getScheme());
    content.put("port", preparePort(request));

    if (!request.getAttributes().isEmpty())
      content.put("attributes", request.getAttributes());

    prepareHeaders(request).ifPresent(headers -> content.put("headers", headers));
    prepareBody(request).ifPresent(body -> content.put("body", body));

    return content;
  }

  private Map<String, Object> response(final Correlation correlation, final HttpResponse response) throws IOException {
    final Map<String, Object> content = new LinkedHashMap<>();

    content.put("origin", response.getOrigin().name().toLowerCase(Locale.ROOT));
    content.put("type", "response");
    content.put("correlation", correlation.getId());
    content.put("duration", correlation.getDuration().toMillis());
    content.put("protocol", response.getProtocolVersion());
    content.put("status", response.getStatus());

    if (!response.getAttributes().isEmpty()) {
      content.put("attributes", response.getAttributes());
    }

    prepareHeaders(response).ifPresent(headers -> content.put("headers", headers));
    prepareBody(response).ifPresent(body -> content.put("body", body));

    return content;
  }

  private String preparePort(final HttpRequest request) {
    return request.getPort()
        .map(Object::toString)
        .orElse(null);
  }

  private Optional<Map<String, List<String>>> prepareHeaders(final HttpMessage message) {
    final Map<String, List<String>> headers = message.getHeaders();
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      headers.put(entry.getKey(),
          logProperties.isSensitiveField(entry.getKey()) ?
              entry.getValue().stream().map(MaskingHelper::masking).toList() :
              entry.getValue()
      );
    }
    return Optional.ofNullable(headers.isEmpty() ? null : headers);
  }

  private Optional<Object> prepareBody(final HttpMessage message) throws IOException {
    final String body = message.getBodyAsString();

    if (!ContentType.isJsonMediaType(message.getContentType())) {
      return Optional.ofNullable(body.isEmpty() ? null : body);
    }
    return Optional.ofNullable(body.isEmpty() ? null : body)
        .map(r -> MaskingHelper.maskingJson(gson, logProperties.getSensitiveFields(), r));
  }
}