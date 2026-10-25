package com.nantaaditya.example.model.constant;

public enum AuthType {
  NONE,
  API_KEY,
  BASIC,
  BEARER_STATIC,
  OAUTH,
  HMAC,
  RSA,
  /** Signs {@code timestamp + "." + body} with HMAC-SHA256 and sends the ISO-8601 timestamp and the signature. */
  HMAC_TIMESTAMP
}
