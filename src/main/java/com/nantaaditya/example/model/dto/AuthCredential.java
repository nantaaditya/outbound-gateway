package com.nantaaditya.example.model.dto;

import com.nantaaditya.example.model.constant.TokenInjectLocation;

public record AuthCredential(String keyName, String value, TokenInjectLocation location) {

  public static AuthCredential empty() {
    return new AuthCredential(null, null, null);
  }

  public boolean isEmpty() {
    return keyName == null || value == null;
  }
}
