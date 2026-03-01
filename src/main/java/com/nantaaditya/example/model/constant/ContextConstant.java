package com.nantaaditya.example.model.constant;

import lombok.Getter;

public enum ContextConstant {
  CLIENT_ID("clientId"),
  REQUEST_ID("requestId"),
  REQUEST_TIME("requestTime"),
  RECEIVED_TIME("receivedTime"),
  RESPONSE_CODE("responseCode"),
  RESPONSE_DESCRIPTION("responseDescription"),
  RESPONSE_TIME("responseTime");

  @Getter
  private String value;

  ContextConstant(String value) {
    this.value = value;
  }
}
