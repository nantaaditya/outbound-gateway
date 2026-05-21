package com.nantaaditya.example.model.logger;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.beans.Transient;
import lombok.Getter;
import org.apache.logging.log4j.message.Message;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@SuppressWarnings("java:S1948")
public class AppLogMessage implements Message {
  private String message;
  @JsonProperty("http_request")
  private JsonLogHttpRequest httpRequest;
  @JsonProperty("http_response")
  private JsonLogHttpResponse httpResponse;
  @JsonProperty("iso_message")
  private JsonLogIsoMessage isoMessage;
  private JsonLogError error;
  @JsonProperty("additional_data")
  private Object additionalData;
  @JsonIgnore
  private Object[] parameters = new Object[0];

  private AppLogMessage() {}

  public static AppLogMessage message(String message, Object... parameters) {
    AppLogMessage logMessage = new AppLogMessage();
    logMessage.message = message;
    logMessage.parameters = parameters;
    return logMessage;
  }

  public AppLogMessage httpRequest(JsonLogHttpRequest httpRequest) {
    this.httpRequest = httpRequest;
    return this;
  }

  public AppLogMessage httpResponse(JsonLogHttpResponse httpResponse) {
    this.httpResponse = httpResponse;
    return this;
  }

  public AppLogMessage isoMessage(JsonLogIsoMessage isoMessage) {
    this.isoMessage = isoMessage;
    return this;
  }

  public AppLogMessage error(Throwable error) {
    this.error = JsonLogError.create(error);
    return this;
  }

  public AppLogMessage additionalData(Object additionalData) {
    this.additionalData = additionalData;
    return this;
  }

  @Override
  @JsonIgnore
  public String getFormattedMessage() {
    return format();
  }

  @Override
  public Object[] getParameters() {
    return this.parameters.clone();
  }

  @Override
  public Throwable getThrowable() {
    return null;
  }

  @Transient
  private String format() {
    StringBuilder sb = new StringBuilder();
    int argIndex = 0;

    for (int i = 0; i < this.message.length(); i++) {
      if (i < this.message.length() - 1 && this.message.charAt(i) == '{' && this.message.charAt(i + 1) == '}') {
        if (argIndex < this.parameters.length) {
          sb.append(this.parameters[argIndex++]);
        } else {
          sb.append("{}");
        }
        i++;
      } else {
        sb.append(this.message.charAt(i));
      }
    }

    return sb.toString();
  }
}