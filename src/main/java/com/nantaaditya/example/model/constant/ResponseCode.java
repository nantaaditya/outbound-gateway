package com.nantaaditya.example.model.constant;

import lombok.Getter;

@Getter
public enum ResponseCode {
  SUCCESS("000", "success"),
  BAD_REQUEST("400", "bad request"),
  INTERNAL_ERROR("500", "internal error"),
  INVALID_PARAMS("900", "invalid parameters");

  private String code;
  private String message;

  ResponseCode(String code, String message) {
    this.code = code;
    this.message = message;
  }
}
