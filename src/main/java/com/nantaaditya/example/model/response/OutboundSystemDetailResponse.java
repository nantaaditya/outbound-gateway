package com.nantaaditya.example.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutboundSystemDetailResponse(
    Long id,
    String code,
    String name,
    String description,
    String httpMethod,
    String baseUrl,
    String apiPath,
    boolean active,
    int idempotencyTtlHours,
    ResilienceDetail resilience,
    Map<String, Object> headers,
    AuthDetail authentication,
    ResponseMapperDetail responseMapper,
    HostConfigDetail hostConfiguration
) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ResilienceDetail(
      int readTimeOutMs,
      CircuitBreakerDetail circuitBreaker,
      BulkheadDetail bulkhead,
      RateLimiterDetail rateLimiter,
      RetryDetail retry,
      boolean circuitBreakerEnabled,
      boolean bulkheadEnabled,
      boolean rateLimiterEnabled,
      boolean retryEnabled
  ) {
    public static ResilienceDetail from(OutboundResilienceConfiguration cfg) {
      if (cfg == null) return null;
      return new ResilienceDetail(
          cfg.getReadTimeOutMs(),
          new CircuitBreakerDetail(
              cfg.getCbFailureRateThreshold(),
              cfg.getCbSlowCallRateThreshold(),
              cfg.getCbSlowCallDurationMs(),
              cfg.getCbPermittedInHalfOpen(),
              cfg.getCbSlidingWindowSize(),
              cfg.getCbWaitDurationOpenMs()
          ),
          new BulkheadDetail(
              cfg.getBhMaxConcurrentCalls(),
              cfg.getBhMaxWaitDurationMs()
          ),
          new RateLimiterDetail(
              cfg.getRlLimitForPeriod(),
              cfg.getRlLimitRefreshPeriodMs(),
              cfg.getRlTimeoutDurationMs()
          ),
          new RetryDetail(
              cfg.getRetryMaxAttempts(),
              cfg.getRetryWaitDurationMs(),
              cfg.getRetryOnStatusCodes()
          ),
          cfg.isCircuitBreakerEnabled(),
          cfg.isBulkheadEnabled(),
          cfg.isRateLimiterEnabled(),
          cfg.isRetryEnabled()
      );
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CircuitBreakerDetail(
      float failureRateThreshold,
      float slowCallRateThreshold,
      int slowCallDurationMs,
      int permittedInHalfOpen,
      int slidingWindowSize,
      int waitDurationOpenMs
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record BulkheadDetail(
      int maxConcurrentCalls,
      int maxWaitDurationMs
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RateLimiterDetail(
      int limitForPeriod,
      int limitRefreshPeriodMs,
      int timeoutDurationMs
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RetryDetail(
      int retryMaxAttempts,
      int retryWaitDurationMs,
      String retryOnStatusCodes
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AuthDetail(
      AuthType authType,
      boolean autoRefresh,
      String tokenMethod,
      String tokenUrl,
      String tokenBody,
      String tokenPrefix,
      TokenInjectLocation tokenInjectLocation,
      String tokenKeyName,
      String tokenResponsePath,
      String tokenResponseExpiryPath
  ) {
    public static AuthDetail from(OutboundAuthConfiguration cfg) {
      if (cfg == null) return null;
      return new AuthDetail(
          cfg.getAuthType(),
          cfg.isAutoRefresh(),
          cfg.getTokenMethod(),
          cfg.getTokenUrl(),
          cfg.getTokenBody(),
          cfg.getTokenPrefix(),
          cfg.getTokenInjectLocation(),
          cfg.getTokenKeyName(),
          cfg.getTokenResponsePath(),
          cfg.getTokenResponseExpiryPath()
      );
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ResponseMapperDetail(
      ConverterType converterType,
      String mappingScript,
      Map<String, Integer> mappingStatus,
      boolean active
  ) {
    public static ResponseMapperDetail from(OutboundResponseMapperConfiguration cfg) {
      if (cfg == null) return null;
      return new ResponseMapperDetail(
          cfg.getConverterType(),
          cfg.getMappingScript(),
          cfg.getMappingStatus(),
          cfg.isActive()
      );
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record HostConfigDetail(
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
    public static HostConfigDetail from(OutboundHostConfiguration cfg) {
      if (cfg == null) return null;
      return new HostConfigDetail(
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

  public static OutboundSystemDetailResponse from(
      OutboundSystem system,
      OutboundResilienceConfiguration resilience,
      Map<String, Object> headers,
      OutboundAuthConfiguration auth,
      OutboundResponseMapperConfiguration responseMapper
  ) {
    return from(system, resilience, headers, auth, responseMapper, null);
  }

  public static OutboundSystemDetailResponse from(
      OutboundSystem system,
      OutboundResilienceConfiguration resilience,
      Map<String, Object> headers,
      OutboundAuthConfiguration auth,
      OutboundResponseMapperConfiguration responseMapper,
      OutboundHostConfiguration hostConfig
  ) {
    return new OutboundSystemDetailResponse(
        system.getId(),
        system.getCode(),
        system.getName(),
        system.getDescription(),
        system.getHttpMethod(),
        system.getBaseUrl(),
        system.getApiPath(),
        system.isActive(),
        system.getIdempotencyTtlHours(),
        ResilienceDetail.from(resilience),
        headers,
        AuthDetail.from(auth),
        ResponseMapperDetail.from(responseMapper),
        HostConfigDetail.from(hostConfig)
    );
  }
}
