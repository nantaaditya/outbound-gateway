package com.nantaaditya.example.model.constant;

import lombok.Getter;

@Getter
public enum JobConstant {
  REQUEST("request"),
  REQUEST_ID("request_id"),
  ERROR_MESSAGE("error_message"),
  ERROR_ROOT_CAUSE("error_root_cause");

  private String name;

  JobConstant(String name) {
    this.name = name;
  }
}
