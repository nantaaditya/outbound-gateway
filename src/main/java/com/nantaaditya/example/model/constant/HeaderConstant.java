package com.nantaaditya.example.model.constant;

import java.beans.Transient;
import java.util.stream.Stream;
import lombok.Getter;

@Getter
public enum HeaderConstant {
  CLIENT_ID("x-client-id"),
  REQUEST_ID("x-request-id"),
  REQUEST_TIME("x-request-time"),
  RECEIVED_TIME("x-received-time");

  private String header;

  HeaderConstant(String header) {
    this.header = header;
  }

  @Transient
  public static boolean contains(String header) {
    return Stream.of(values())
        .anyMatch(v -> v.getHeader().equals(header));
  }
}
