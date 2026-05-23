package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.exception.OutboundSystemNotFoundException;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.request.admin.CreateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpdateOutboundSystemRequest;
import com.nantaaditya.example.model.request.admin.UpsertAuthRequest;
import com.nantaaditya.example.model.request.admin.UpsertHeadersRequest;
import com.nantaaditya.example.model.request.admin.UpsertResilienceRequest;
import com.nantaaditya.example.model.request.admin.UpsertResponseMapperRequest;
import com.nantaaditya.example.model.response.OutboundSystemDetailResponse;
import com.nantaaditya.example.model.response.OutboundSystemResponse;
import com.nantaaditya.example.model.response.TestConnectionResponse;
import com.nantaaditya.example.repository.OutboundAuthConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHeaderConfigurationRepository;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResilienceConfigurationRepository;
import com.nantaaditya.example.repository.OutboundResponseMapperConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.GatewayService;
import com.nantaaditya.example.service.OutboundService;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Log4j2
@Service
@RequiredArgsConstructor
public class OutboundServiceImpl implements OutboundService {

  private final OutboundSystemRepository systemRepository;
  private final OutboundResilienceConfigurationRepository resilienceRepository;
  private final OutboundHeaderConfigurationRepository headerRepository;
  private final OutboundAuthConfigurationRepository authRepository;
  private final OutboundResponseMapperConfigurationRepository mapperRepository;
  private final OutboundHostConfigurationRepository hostConfigRepository;
  private final GatewayService gatewayService;
  private final GatewayWebClientBuilder contextBuilder;
  private final EncryptionHelper encryptionHelper;
  private final ResilienceRegistryService resilienceRegistryService;
  private final TransactionalOperator transactionalOperator;

  private static final Duration TEST_CONNECTION_TIMEOUT = Duration.ofSeconds(5);
  private static final int MAX_LIST_SIZE = 1000;

  private static final Set<String> SENSITIVE_HEADER_NAMES = Set.of(
      "authorization", "proxy-authorization", "x-api-key", "x-auth-token",
      "x-secret", "x-client-secret", "x-access-token"
  );

  @Override
  public Mono<OutboundSystemResponse> create(CreateOutboundSystemRequest request) {
    return systemRepository.save(OutboundSystem.create(request))
        .doOnSuccess(s -> log.info(AppLogMessage.message("#OutboundService - created outbound system code={}", s.getCode())))
        .map(OutboundSystemResponse::from);
  }

  @Override
  public Mono<List<OutboundSystemResponse>> list() {
    return systemRepository.findAll()
        .take(MAX_LIST_SIZE)
        .map(OutboundSystemResponse::from)
        .collectList();
  }

  @Override
  public Mono<OutboundSystemDetailResponse> getDetail(String code) {
    return requireSystem(code)
        .flatMap(system -> {
          long systemId = system.getId();
          Mono<Optional<OutboundResilienceConfiguration>> resilienceMono = resilienceRepository
              .findByOutboundSystemId(systemId)
              .map(Optional::of)
              .defaultIfEmpty(Optional.empty());
          Mono<List<Map<String, Object>>> headersMono = headerRepository.findByOutboundSystemId(systemId)
              .map(h -> scrubSensitiveHeaders(h.getHeaders()))
              .collectList();
          Mono<Optional<OutboundAuthConfiguration>> authMono = authRepository
              .findByOutboundSystemId(systemId)
              .map(Optional::of)
              .defaultIfEmpty(Optional.empty());
          Mono<Optional<OutboundResponseMapperConfiguration>> mapperMono = mapperRepository
              .findByOutboundSystemId(systemId)
              .map(Optional::of)
              .defaultIfEmpty(Optional.empty());

          return Mono.zip(resilienceMono, headersMono, authMono, mapperMono)
              .map(t -> OutboundSystemDetailResponse.from(
                  system,
                  t.getT1().orElse(null),
                  t.getT2(),
                  t.getT3().orElse(null),
                  t.getT4().orElse(null))
              );
        });
  }

  @Override
  public Mono<OutboundSystemResponse> update(String code, UpdateOutboundSystemRequest request) {
    return requireSystem(code)
        .flatMap(system -> systemRepository.save(system.updated(request)))
        .doOnSuccess(s -> log.info(AppLogMessage.message("#OutboundService - updated outbound system code={}", code)))
        .map(OutboundSystemResponse::from);
  }

  @Override
  public Mono<Boolean> delete(String code) {
    return requireSystem(code)
        .flatMap(system -> {
          long systemId = system.getId();
          return resilienceRepository.deleteByOutboundSystemId(systemId)
              .then(headerRepository.deleteByOutboundSystemId(systemId))
              .then(authRepository.deleteByOutboundSystemId(systemId))
              .then(mapperRepository.deleteByOutboundSystemId(systemId))
              .then(systemRepository.delete(system))
              .thenReturn(Boolean.TRUE)
              .as(transactionalOperator::transactional)
              .doOnSuccess(v -> {
                gatewayService.evictContext(code);
                resilienceRegistryService.reload(code);
                log.info(AppLogMessage.message("#OutboundService - deleted outbound system code={}", code));
              });
        });
  }

  @Override
  public Mono<Boolean> upsertResilience(String code, UpsertResilienceRequest request) {
    return requireSystem(code)
        .flatMap(system -> resilienceRepository.findByOutboundSystemId(system.getId())
            .defaultIfEmpty(OutboundResilienceConfiguration.builder()
                .outboundSystemId(system.getId())
                .build())
            .flatMap(existing -> resilienceRepository.save(existing.updated(system.getId(), request))))
        .doOnSuccess(s -> {
          resilienceRegistryService.reload(code);
          gatewayService.evictContext(code);
          log.info(AppLogMessage.message("#OutboundService - upserted resilience config code={}", code));
        })
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<Boolean> upsertHeaders(String code, UpsertHeadersRequest request) {
    return requireSystem(code)
        .flatMap(system -> {
          Map<String, Object> headersAsObjectMap = new HashMap<>();
          if (request.headers() != null) {
            request.headers().forEach(headersAsObjectMap::put);
          }
          return headerRepository.deleteByOutboundSystemId(system.getId())
              .then(headerRepository.save(OutboundHeaderConfiguration.builder()
                  .outboundSystemId(system.getId())
                  .headers(headersAsObjectMap)
                  .build()))
              .doOnSuccess(s -> {
                gatewayService.evictContext(code);
                log.info(AppLogMessage.message("#OutboundService - upserted headers code={}", code));
              })
              .as(transactionalOperator::transactional);
        })
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<Boolean> upsertAuth(String code, UpsertAuthRequest request) {
    return requireSystem(code)
        .flatMap(system ->
            Mono.fromCallable(() -> new byte[][]{
                    encryptIfNotNull(request.apiKey()),
                    encryptIfNotNull(request.clientId()),
                    encryptIfNotNull(request.clientSecret())
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(encrypted ->
                    authRepository.findByOutboundSystemId(system.getId())
                        .defaultIfEmpty(OutboundAuthConfiguration.builder()
                            .outboundSystemId(system.getId())
                            .build())
                        .flatMap(existing -> authRepository.save(
                            existing.updated(system.getId(), encrypted[0], encrypted[1], encrypted[2], request)))
                        .doOnSuccess(s -> {
                          gatewayService.evictContext(code);
                          log.info(AppLogMessage.message("#OutboundService - upserted auth config code={}", code));
                        })
                )
        )
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<Boolean> upsertResponseMapper(String code, UpsertResponseMapperRequest request) {
    return requireSystem(code)
        .flatMap(system -> mapperRepository.findByOutboundSystemId(system.getId())
            .defaultIfEmpty(OutboundResponseMapperConfiguration.builder()
                .outboundSystemId(system.getId())
                .build())
            .flatMap(existing -> mapperRepository.save(existing.updated(system.getId(), request))))
        .doOnSuccess(s -> {
          gatewayService.evictContext(code);
          log.info(AppLogMessage.message("#OutboundService - upserted response mapper code={}", code));
        })
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<Boolean> reload(String code) {
    return requireSystem(code)
        .flatMap(system -> Mono.fromRunnable(() -> {
          gatewayService.evictContext(code);
          resilienceRegistryService.reload(code);
          log.info(AppLogMessage.message("#OutboundService - reloaded outbound system code={}", code));
        }).subscribeOn(Schedulers.boundedElastic()))
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<TestConnectionResponse> testConnection(String code) {
    return requireSystem(code)
        .flatMap(system ->
            hostConfigRepository.findByBaseUrl(system.getBaseUrl())
                .defaultIfEmpty(defaultHostConfig(system.getBaseUrl()))
                .flatMap(hostConfig -> {
                  var client = contextBuilder.buildDisposableWebClient(hostConfig);
                  return client.method(system.getHttpMethodEnum())
                      .uri(system.getFullPath())
                      .exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new TestConnectionResponse(status < 500, status, body));
                      })
                      .timeout(TEST_CONNECTION_TIMEOUT)
                      .doOnSuccess(r -> log.info(AppLogMessage.message("#OutboundService - testConnection code={} httpStatus={} success={}", code, r.httpStatus(), r.success())))
                      .onErrorResume(e -> {
                        log.warn(AppLogMessage.message("#OutboundService - testConnection failed code={} error={}", code, e.getClass().getSimpleName()).error(e));
                        return Mono.just(new TestConnectionResponse(false, 0, "Connection failed: " + e.getClass().getSimpleName()));
                      });
                })
        );
  }

  private Map<String, Object> scrubSensitiveHeaders(Map<String, Object> headers) {
    if (headers == null || headers.isEmpty()) return Map.of();
    Map<String, Object> scrubbed = new HashMap<>();
    headers.forEach((k, v) -> scrubbed.put(k,
        SENSITIVE_HEADER_NAMES.contains(k.toLowerCase()) ? "***" : v));
    return scrubbed;
  }

  private Mono<OutboundSystem> requireSystem(String code) {
    return systemRepository.findByCode(code)
        .switchIfEmpty(Mono.error(new OutboundSystemNotFoundException(code)));
  }

  private OutboundHostConfiguration defaultHostConfig(String baseUrl) {
    return OutboundHostConfiguration.testDefault(baseUrl);
  }

  private byte[] encryptIfNotNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return encryptionHelper.encryptString(value);
  }

}
