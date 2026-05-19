package com.nantaaditya.example.configuration;


import com.nantaaditya.example.helper.ContextHelper;
import com.nantaaditya.example.helper.MaskingHelper;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.logger.JsonLogHttpRequest;
import com.nantaaditya.example.model.logger.JsonLogHttpResponse;
import com.nantaaditya.example.properties.LogProperties;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.boot.actuate.web.exchanges.HttpExchange.Request;
import org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Log4j2
@Component
@RequiredArgsConstructor
public class TraceLogConfiguration implements HttpExchangeRepository {

  private final ContextHelper contextHelper;
  private final LogProperties logProperties;
  private final AtomicReference<HttpExchange> httpTrace = new AtomicReference<>();

  @Override
  public List<HttpExchange> findAll() {
    return Collections.singletonList(httpTrace.get());
  }

  @Override
  public void add(HttpExchange trace) {
    HttpExchange.Request request = trace.getRequest();
    HttpExchange.Response response = trace.getResponse();

    Map<String, List<String>> headers = request.getHeaders();
    if (headers == null || !headers.containsKey(HeaderConstant.REQUEST_ID.getHeader())) {
      log.warn(AppLogMessage.message("#Trace - no request headers found"));
      return;
    }

    String requestId = headers.get(HeaderConstant.REQUEST_ID.getHeader()).getFirst();

    if (!logProperties.enableTraceLog()) {
      contextHelper.cleanUp(requestId);
      return;
    }

    if (logProperties.isIgnoredTraceLogPath(request.getUri().getPath())) {
      contextHelper.cleanUp(requestId);
      return;
    }

    // request
    MultiValueMap<String, String> requestHeaders = new LinkedMultiValueMap<>();
    for (Entry<String, List<String>> entry : headers.entrySet()) {
      if (isInternalHeader(entry.getKey())) {
        maskHeader(requestHeaders, entry);
      }
    }
    JsonLogHttpRequest httpRequest = new JsonLogHttpRequest(
        request.getMethod(),
        getPath(request),
        requestHeaders,
        null
    );

    // response
    MultiValueMap<String, String> responseHeaders = new LinkedMultiValueMap<>();
    for (Entry<String, List<String>> entry : headers.entrySet()) {
      if (isInternalHeader(entry.getKey())) {
        maskHeader(responseHeaders, entry);
      }
    }
    JsonLogHttpResponse httpResponse = new JsonLogHttpResponse(
        request.getMethod(),
        getPath(request),
        String.valueOf(response.getStatus()),
        String.format("%s ms", trace.getTimeTaken().toMillis()),
        responseHeaders,
        null
    );

    log.info(AppLogMessage.message("#Trace").httpRequest(httpRequest).httpResponse(httpResponse));
    contextHelper.cleanUp(requestId);
  }

  private static String getPath(Request request) {
    return (request.getUri().getRawQuery() != null) ?
        request.getUri().getPath().concat("?").concat(request.getUri().getRawQuery())
        : request.getUri().getPath();
  }

  private boolean isInternalHeader(String headerKey) {
    return Stream.of(HeaderConstant.values())
        .anyMatch(h -> h.getHeader().equals(headerKey));
  }

  private void maskHeader(MultiValueMap<String, String> maskedHeaders,
      Entry<String, List<String>> headers) {
    maskedHeaders.put(headers.getKey(), logProperties.isSensitiveField(headers.getKey()) ?
        headers.getValue().stream().map(MaskingHelper::masking).toList()  : headers.getValue());
  }
}
