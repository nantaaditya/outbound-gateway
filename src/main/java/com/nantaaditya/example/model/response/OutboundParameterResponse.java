package com.nantaaditya.example.model.response;

import com.nantaaditya.example.entity.OutboundParameter;
import com.nantaaditya.example.model.constant.ParameterInjectLocation;

/** Deliberately has no value field — parameter values are write-only. */
public record OutboundParameterResponse(
    String key,
    ParameterInjectLocation location,
    String targetName,
    boolean active
) {

  public static OutboundParameterResponse from(OutboundParameter parameter) {
    return new OutboundParameterResponse(
        parameter.getParamKey(),
        parameter.getInjectLocation(),
        parameter.getTargetName(),
        parameter.isActive()
    );
  }
}
