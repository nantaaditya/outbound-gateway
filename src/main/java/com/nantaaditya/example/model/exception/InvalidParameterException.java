package com.nantaaditya.example.model.exception;

import lombok.Getter;

@Getter
public class InvalidParameterException extends RuntimeException {

  private final String field;

  public InvalidParameterException(String field, String message) {
    super(message);
    this.field = field;
  }
}
