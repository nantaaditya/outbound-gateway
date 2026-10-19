package com.nantaaditya.example.model.dto;

import com.nantaaditya.example.model.constant.ParameterInjectLocation;

/** A parameter whose value has been decrypted for a single outbound call; never persisted or logged. */
public record ResolvedParameter(
    String key,
    ParameterInjectLocation location,
    String targetName,
    String value
) {

  @Override
  public String toString() {
    return "ResolvedParameter[key=" + key + ", location=" + location + ", targetName=" + targetName + "]";
  }
}
