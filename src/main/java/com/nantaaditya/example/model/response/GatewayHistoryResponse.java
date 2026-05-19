package com.nantaaditya.example.model.response;

import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.helper.JsonHelper;
import java.time.LocalDateTime;

public record GatewayHistoryResponse(
    Long id,
    Long outboundSystemId,
    String idempotencyKey,
    String callerService,
    String requestMethod,
    String requestUrl,
    Object requestPayload,
    Integer responseStatus,
    Object responsePayload,
    Integer durationMs,
    Integer convertedStatus,
    Object convertedResponse,
    LocalDateTime createdDate
) {

  public static GatewayHistoryResponse from(OutboundHistory history, JsonHelper jsonHelper) {
    return new GatewayHistoryResponse(
        history.getId(),
        history.getOutboundSystemId(),
        history.getIdempotencyKey(),
        history.getCallerService(),
        history.getRequestMethod(),
        history.getRequestUrl(),
        jsonHelper.parseJsonOrRaw(history.getRequestPayload()),
        history.getResponseStatus(),
        jsonHelper.parseJsonOrRaw(history.getResponsePayload()),
        history.getDurationMs(),
        history.getConvertedStatus(),
        jsonHelper.parseJsonOrRaw(history.getConvertedResponse()),
        history.getCreatedDate()
    );
  }
}
