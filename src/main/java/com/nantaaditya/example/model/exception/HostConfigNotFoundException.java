package com.nantaaditya.example.model.exception;

public class HostConfigNotFoundException extends RuntimeException {

  public HostConfigNotFoundException(long id) {
    super("Host config not found: id=" + id);
  }
}
