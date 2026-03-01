package com.nantaaditya.example.model.constant;

import lombok.Getter;

@Getter
public enum ObservationConstant {
  PUBLIC_API("api.public"),
  INTERNAL_API("api.internal"),
  UNKNOWN_API("api.unknown");

  private String name;

  ObservationConstant(String name) {
    this.name = name;
  }

  public static ObservationConstant from(String path) {
    if (path.startsWith("/internal-api")) {
      return INTERNAL_API;
    } else if (path.startsWith("/api")) {
      return PUBLIC_API;
    }
    return UNKNOWN_API;
  }
}
