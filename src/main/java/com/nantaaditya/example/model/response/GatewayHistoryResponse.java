package com.nantaaditya.example.model.response;

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
) {}
