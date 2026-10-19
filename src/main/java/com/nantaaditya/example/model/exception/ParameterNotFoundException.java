package com.nantaaditya.example.model.exception;

import java.util.Collection;

public class ParameterNotFoundException extends RuntimeException {

  public ParameterNotFoundException(Collection<String> keys) {
    super("Outbound parameter not found or inactive: keys=" + keys);
  }
}
