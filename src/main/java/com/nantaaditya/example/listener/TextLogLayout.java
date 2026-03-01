package com.nantaaditya.example.listener;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nantaaditya.example.helper.DateTimeHelper;
import com.nantaaditya.example.helper.StringHelper;
import com.nantaaditya.example.model.logger.AppLogMessage;
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
    name = "TextLogLayout",
    category = Node.CATEGORY,
    elementType = Layout.ELEMENT_TYPE,
    printObject = true
)
public class TextLogLayout extends AbstractStringLayout {

  private final ObjectMapper mapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .setDefaultPropertyInclusion(Include.NON_NULL)
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
      .disable(MapperFeature.USE_ANNOTATIONS);
  private final String application;

  protected TextLogLayout(String application) {
    super(StandardCharsets.UTF_8);
    this.application = application;
  }

  @PluginFactory
  public static TextLogLayout createLayout(
      @PluginAttribute(value = "application", defaultString = "UNKNOWN") final String application
  ) {
    return new TextLogLayout(application);
  }

  @Override
  public String toSerializable(LogEvent event) {
    StringBuilder sb = new StringBuilder();
    sb.append(DateTimeHelper.getDateInFormat(event.getTimeMillis(), DateTimeHelper.LOG_TIMESTAMP_FORMATTER));
    sb.append(" --- ");
    sb.append(String.format("[%s]", this.application));
    sb.append(" ");
    sb.append(String.format("[%s] ", StringHelper.logPrepend(event.getThreadName(), 10, ' ')));
    sb.append(event.getLevel().toString());

    Map<String, String> mdc = event.getContextData().toMap();
    sb.append(String.format(" | requestId: [%s]", mdc.get("reqId")));
    sb.append(String.format(" - trace: [%s-%s]", mdc.get("traceId"), mdc.get("spanId")));
    sb.append(String.format(" | %s : ", StringHelper.logPrepend(event.getLoggerName(), 36, ' ')));

    if (event.getMessage() instanceof AppLogMessage a) {
      try {
        sb.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(a));
      } catch (Exception e) {
        sb.append(a.getFormattedMessage());
      }
    } else {
      sb.append(event.getMessage().getFormattedMessage());
    }

    return sb.toString() + System.lineSeparator();
  }

}