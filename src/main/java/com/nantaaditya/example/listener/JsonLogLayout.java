package com.nantaaditya.example.listener;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nantaaditya.example.helper.DateTimeHelper;
import com.nantaaditya.example.helper.StringHelper;
import com.nantaaditya.example.model.logger.AppLogMessage;
import io.micrometer.core.instrument.util.StringEscapeUtils;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.AbstractStringLayout;

@Plugin(
    name = "JsonLogLayout",
    category = Node.CATEGORY,
    elementType = Layout.ELEMENT_TYPE,
    printObject = true
)
public class JsonLogLayout extends AbstractStringLayout {

  private final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .setDefaultPropertyInclusion(Include.NON_NULL)
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
      .disable(MapperFeature.USE_ANNOTATIONS);
  private final String version;

  protected JsonLogLayout(String version) {
    super(StandardCharsets.UTF_8);
    this.version = version;
  }

  @PluginFactory
  public static JsonLogLayout createLayout(
      @PluginAttribute(value = "version", defaultString = "UNKNOWN") final String version
  ) {
    return new JsonLogLayout(version);
  }

  @Override
  public String toSerializable(LogEvent event) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("timestamp", DateTimeHelper.getDateInFormat(event.getTimeMillis(), DateTimeHelper.LOG_TIMESTAMP_FORMATTER));
    root.put("level", event.getLevel().toString());
    root.put("app_version", this.version);
    root.put("class", StringHelper.logPrepend(event.getLoggerName(), 36, ' '));

    ObjectNode context = root.putObject("context");
    if (event.getMessage() instanceof AppLogMessage j) {
      parseJsonLogMessage(j, context);
    } else {
      context.put("message", StringEscapeUtils.escapeJson(event.getMessage().getFormattedMessage()));
    }

    Map<String, String> mdc = event.getContextData().toMap();
    root.put("trace_id", mdc.get("traceId"));
    root.put("span_id", mdc.get("spanId"));
    root.put("request_id", mdc.get("reqId"));

    return root.toString() + System.lineSeparator();
  }

  private void parseJsonLogMessage(AppLogMessage j, ObjectNode context) {
    context.put("message", StringEscapeUtils.escapeJson(j.getFormattedMessage()));

    if (j.getHttpRequest() != null) {
      ObjectNode httpRequest = objectMapper.valueToTree(j.getHttpRequest());
      context.set("http_request", httpRequest);
    }

    if (j.getHttpResponse() != null) {
      ObjectNode httpResponse = objectMapper.valueToTree(j.getHttpResponse());
      context.set("http_response", httpResponse);
    }

    if (j.getIsoMessage() != null) {
      ObjectNode isoMessage = objectMapper.valueToTree(j.getIsoMessage());
      context.set("iso_message", isoMessage);
    }

    if (j.getAdditionalData() != null) {
      try {
        ObjectNode additionalData = objectMapper.valueToTree(j.getAdditionalData());
        context.set("additional_data", additionalData);
      } catch (Exception e) {
        context.put("additional_data", StringEscapeUtils.escapeJson(j.getAdditionalData().toString()));
      }
    }

    if (j.getError() != null) {
      ObjectNode error = objectMapper.valueToTree(j.getError());
      context.set("error", error);
    }

  }
}