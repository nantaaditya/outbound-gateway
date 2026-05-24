package com.nantaaditya.example.model.response;

import com.nantaaditya.example.entity.OutboundHostConfiguration;

public record OutboundHostConfigResponse(
    Long id,
    String baseUrl,
    int connectTimeOutMs,
    int maxConnections,
    int pendingAcquireMaxCount,
    int pendingAcquireTimeoutMs,
    boolean disableSslVerification,
    boolean useProxy,
    String proxyHost,
    int proxyPort
) {
  public static OutboundHostConfigResponse from(OutboundHostConfiguration cfg) {
    if (cfg == null) return null;
    return new OutboundHostConfigResponse(
        cfg.getId(),
        cfg.getBaseUrl(),
        cfg.getConnectTimeOutMs(),
        cfg.getMaxConnections(),
        cfg.getPendingAcquireMaxCount(),
        cfg.getPendingAcquireTimeoutMs(),
        cfg.isDisableSslVerification(),
        cfg.isUseProxy(),
        cfg.getProxyHost(),
        cfg.getProxyPort()
    );
  }
}
