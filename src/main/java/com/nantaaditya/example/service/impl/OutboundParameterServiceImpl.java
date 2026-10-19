package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.entity.OutboundParameter;
import com.nantaaditya.example.helper.EncryptionHelper;
import com.nantaaditya.example.helper.OutboundParameterCache;
import com.nantaaditya.example.helper.ParameterInjector;
import com.nantaaditya.example.model.exception.HostConfigNotFoundException;
import com.nantaaditya.example.model.exception.InvalidParameterException;
import com.nantaaditya.example.model.exception.ParameterNotFoundException;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.request.admin.UpsertParametersRequest;
import com.nantaaditya.example.model.request.admin.UpsertParametersRequest.Item;
import com.nantaaditya.example.model.response.OutboundParameterResponse;
import com.nantaaditya.example.repository.OutboundHostConfigurationRepository;
import com.nantaaditya.example.repository.OutboundParameterRepository;
import com.nantaaditya.example.service.OutboundParameterService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Admin operations on {@code outbound_parameters}. Values are encrypted on write and never read back.
 *
 * <p>Every write evicts the key from {@link OutboundParameterCache} (after the DB write) so this
 * instance serves the change immediately; other instances converge within the cache TTL.
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class OutboundParameterServiceImpl implements OutboundParameterService {

  private final OutboundParameterRepository parameterRepository;
  private final OutboundHostConfigurationRepository hostConfigRepository;
  private final EncryptionHelper encryptionHelper;
  private final OutboundParameterCache parameterCache;

  @Override
  public Mono<List<OutboundParameterResponse>> upsert(long hostId, UpsertParametersRequest request) {
    return requireHost(hostId)
        .then(Mono.fromRunnable(() -> validate(request)))
        .thenMany(Flux.fromIterable(request.parameters()))
        .concatMap(item -> upsertItem(hostId, item))
        .map(OutboundParameterResponse::from)
        .collectList()
        .doOnSuccess(r -> log.info(AppLogMessage.message(
            "#OutboundParameterService - upserted {} parameter(s) hostId={}", r.size(), hostId)));
  }

  @Override
  public Mono<List<OutboundParameterResponse>> list(long hostId) {
    return requireHost(hostId)
        .thenMany(parameterRepository.findByHostConfigurationId(hostId))
        .map(OutboundParameterResponse::from)
        .collectList();
  }

  @Override
  public Mono<Boolean> delete(long hostId, String key) {
    return requireHost(hostId)
        .then(parameterRepository.findByHostConfigurationIdAndParamKey(hostId, key))
        .switchIfEmpty(Mono.error(new ParameterNotFoundException(List.of(key))))
        .flatMap(existing -> parameterRepository.delete(existing)
            .doOnSuccess(v -> parameterCache.evict(hostId, key)))
        .doOnSuccess(v -> log.info(AppLogMessage.message(
            "#OutboundParameterService - deleted parameter key={} hostId={}", key, hostId)))
        .thenReturn(Boolean.TRUE);
  }

  private Mono<OutboundParameter> upsertItem(long hostId, Item item) {
    return Mono.fromCallable(() -> encryptionHelper.encryptString(item.value()))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(encrypted -> parameterRepository
            .findByHostConfigurationIdAndParamKey(hostId, item.key())
            .map(existing -> existing.updated(encrypted, item.location(), item.targetName(), item.isActive()))
            .defaultIfEmpty(OutboundParameter.create(hostId, item.key(), encrypted,
                item.location(), item.targetName(), item.isActive()))
            .flatMap(parameterRepository::save)
            .doOnNext(saved -> parameterCache.evict(hostId, saved.getParamKey())));
  }

  private void validate(UpsertParametersRequest request) {
    Set<String> seen = new HashSet<>();
    for (Item item : request.parameters()) {
      if (!seen.add(item.key())) {
        throw new InvalidParameterException("parameters", "duplicate key: " + item.key());
      }
      ParameterInjector.validateTarget(item.location(), item.targetName());
      ParameterInjector.validateValue(item.location(), item.value());
    }
  }

  private Mono<Void> requireHost(long hostId) {
    return hostConfigRepository.existsById(hostId)
        .filter(Boolean::booleanValue)
        .switchIfEmpty(Mono.error(new HostConfigNotFoundException(hostId)))
        .then();
  }
}
