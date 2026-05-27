package com.nantaaditya.example.model.response;

import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.helper.JsonHelper;
import java.time.LocalDateTime;

public record GatewayHistoryResponse(
    Long id,
    Long outboundSystemId,
    String idempotencyKey,
    String callerService,
    String requestId,
    String requestMethod,
    String requestUrl,
    Object requestHeaders,
    Object requestPayload,
    Integer responseStatus,
    Object responseHeaders,
    Object responsePayload,
    Integer durationMs,
    Integer convertedStatus,
    Object convertedResponse,
    int retryCount,
    LocalDateTime createdDate
) {

  public static GatewayHistoryResponse from(OutboundHistory history, JsonHelper jsonHelper) {
    return new GatewayHistoryResponse(
        history.getId(),
        history.getOutboundSystemId(),
        history.getIdempotencyKey(),
        history.getCallerService(),
        history.getRequestId(),
        history.getRequestMethod(),
        history.getRequestUrl(),
        history.getRequestHeaders(),
        jsonHelper.parseJsonOrRaw(history.getRequestPayload()),
        history.getResponseStatus(),
        history.getResponseHeaders(),
        jsonHelper.parseJsonOrRaw(history.getResponsePayload()),
        history.getDurationMs(),
        history.getConvertedStatus(),
        jsonHelper.parseJsonOrRaw(history.getConvertedResponse()),
        history.getRetryCount(),
        history.getCreatedDate()
    );
  }
}
