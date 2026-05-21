package com.nantaaditya.example.model.exception;

public class OutboundSystemNotFoundException extends RuntimeException {

  public OutboundSystemNotFoundException(String code) {
    super("Outbound system not found: " + code);
  }
}
