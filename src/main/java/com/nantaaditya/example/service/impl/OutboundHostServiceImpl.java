package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.model.exception.HostConfigNotFoundException;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.request.admin.CreateHostConfigRequest;
import com.nantaaditya.example.model.request.admin.UpdateHostConfigRequest;
import com.nantaaditya.example.model.response.OutboundHostConfigResponse;
import com.nantaaditya.example.model.response.OutboundSystemResponse;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundSystemRepository;
import com.nantaaditya.example.service.GatewayService;
import com.nantaaditya.example.service.OutboundHostService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Log4j2
@Service
@RequiredArgsConstructor
public class OutboundHostServiceImpl implements OutboundHostService {

  private final OutboundHostConfigurationRepository hostConfigRepository;
  private final OutboundSystemRepository systemRepository;
  private final GatewayService gatewayService;
  private final EncryptionHelper encryptionHelper;

  @Override
  public Mono<OutboundHostConfigResponse> create(CreateHostConfigRequest request) {
    return encryptCredentials(request.proxyUsername(), request.proxyPassword())
        .flatMap(credentials -> saveOutboundHostConfiguration(OutboundHostConfiguration.create(request, credentials[0], credentials[1])))
        .doOnSuccess(c -> log.info(AppLogMessage.message("#OutboundHostService - created host config baseUrl={}", c.getBaseUrl())))
        .map(OutboundHostConfigResponse::from);
  }

  @Override
  public Mono<List<OutboundHostConfigResponse>> list(int page, int size) {
    PageRequest pageRequest = PageRequest.of(page, size);
    pageRequest.withSort(Sort.by(Direction.ASC, "createdDate"));

    return hostConfigRepository.findAllBy(pageRequest)
        .map(OutboundHostConfigResponse::from)
        .collectList();
  }

  @Override
  public Mono<OutboundHostConfigResponse> getById(long id) {
    return requireHostConfig(id)
        .map(OutboundHostConfigResponse::from);
  }

  @Override
  public Mono<OutboundHostConfigResponse> update(long id, UpdateHostConfigRequest request) {
    return requireHostConfig(id)
        .flatMap(existing ->
            encryptCredentials(request.proxyUsername(), request.proxyPassword())
                .flatMap(credentials -> saveOutboundHostConfiguration(existing.updated(request, credentials[0], credentials[1])))
        )
        .doOnSuccess(c -> {
          // evicts the host's pool AND every cached system context that still holds it
          gatewayService.evictHost(c.getBaseUrl());
          log.info(AppLogMessage.message("#OutboundHostService - updated host config id={} baseUrl={}", id, c.getBaseUrl()));
        })
        .map(OutboundHostConfigResponse::from);
  }

  @Override
  public Mono<Boolean> reload(long id) {
    return requireHostConfig(id)
        .flatMap(cfg -> Mono.fromRunnable(() -> {
          gatewayService.evictHost(cfg.getBaseUrl());
          log.info(AppLogMessage.message("#OutboundHostService - reloaded host config id={} baseUrl={}", id, cfg.getBaseUrl()));
        }).subscribeOn(Schedulers.boundedElastic()))
        .thenReturn(Boolean.TRUE);
  }

  @Override
  public Mono<List<OutboundSystemResponse>> listSystems(long id) {
    return requireHostConfig(id)
        .flatMapMany(hostConfig -> systemRepository.findByHostConfigurationId(hostConfig.getId())
            .map(system -> OutboundSystemResponse.from(system, hostConfig)))
        .collectList();
  }

  private Mono<OutboundHostConfiguration> requireHostConfig(long id) {
    return hostConfigRepository.findById(id)
        .switchIfEmpty(Mono.error(new HostConfigNotFoundException(id)));
  }

  /** Encrypts proxy credentials on boundedElastic; returns [encryptedUsername, encryptedPassword]. */
  private Mono<byte[][]> encryptCredentials(String username, String password) {
    return Mono.fromCallable(() -> new byte[][]{
        encryptIfNotNull(username),
        encryptIfNotNull(password)
    }).subscribeOn(Schedulers.boundedElastic());
  }

  private byte[] encryptIfNotNull(String value) {
    if (value == null || value.isBlank()) return null; //NOSONAR
    return encryptionHelper.encryptString(value);
  }

  private Mono<OutboundHostConfiguration> saveOutboundHostConfiguration(OutboundHostConfiguration outboundHostConfiguration) {
    return hostConfigRepository.save(outboundHostConfiguration);
  }
}
