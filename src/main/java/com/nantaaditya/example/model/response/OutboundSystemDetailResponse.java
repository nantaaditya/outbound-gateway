package com.nantaaditya.example.model.response;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.constant.TokenInjectLocation;
import java.util.List;
import java.util.Map;

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
    List<Map<String, Object>> headers,
    AuthDetail auth,
    ResponseMapperDetail responseMapper
) {

  public record ResilienceDetail(
      int readTimeOutMs,
      float cbFailureRateThreshold,
      float cbSlowCallRateThreshold,
      int cbSlowCallDurationMs,
      int cbPermittedInHalfOpen,
      int cbSlidingWindowSize,
      int cbWaitDurationOpenMs,
      int bhMaxConcurrentCalls,
      int bhMaxWaitDurationMs,
      int rlLimitForPeriod,
      int rlLimitRefreshPeriodMs,
      int rlTimeoutDurationMs,
      int retryMaxAttempts,
      int retryWaitDurationMs,
      String retryOnStatusCodes,
      boolean circuitBreakerEnabled,
      boolean bulkheadEnabled,
      boolean rateLimiterEnabled,
      boolean retryEnabled
  ) {
    public static ResilienceDetail from(OutboundResilienceConfiguration cfg) {
      if (cfg == null) return null;
      return new ResilienceDetail(
          cfg.getReadTimeOutMs(),
          cfg.getCbFailureRateThreshold(),
          cfg.getCbSlowCallRateThreshold(),
          cfg.getCbSlowCallDurationMs(),
          cfg.getCbPermittedInHalfOpen(),
          cfg.getCbSlidingWindowSize(),
          cfg.getCbWaitDurationOpenMs(),
          cfg.getBhMaxConcurrentCalls(),
          cfg.getBhMaxWaitDurationMs(),
          cfg.getRlLimitForPeriod(),
          cfg.getRlLimitRefreshPeriodMs(),
          cfg.getRlTimeoutDurationMs(),
          cfg.getRetryMaxAttempts(),
          cfg.getRetryWaitDurationMs(),
          cfg.getRetryOnStatusCodes(),
          cfg.isCircuitBreakerEnabled(),
          cfg.isBulkheadEnabled(),
          cfg.isRateLimiterEnabled(),
          cfg.isRetryEnabled()
      );
    }
  }

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

  public static OutboundSystemDetailResponse from(
      OutboundSystem system,
      OutboundResilienceConfiguration resilience,
      List<Map<String, Object>> headers,
      OutboundAuthConfiguration auth,
      OutboundResponseMapperConfiguration responseMapper
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
        ResponseMapperDetail.from(responseMapper)
    );
  }
}
