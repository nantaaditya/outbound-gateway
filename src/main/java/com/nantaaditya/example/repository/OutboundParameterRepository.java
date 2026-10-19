package com.nantaaditya.example.repository;

import com.nantaaditya.example.entity.OutboundParameter;
import java.util.Collection;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface OutboundParameterRepository
    extends ReactiveCrudRepository<OutboundParameter, Long> {

  Flux<OutboundParameter> findByHostConfigurationId(long hostConfigurationId);

  Flux<OutboundParameter> findByHostConfigurationIdAndParamKeyInAndActiveTrue(
      long hostConfigurationId, Collection<String> paramKeys);

  Mono<OutboundParameter> findByHostConfigurationIdAndParamKey(long hostConfigurationId, String paramKey);
}
