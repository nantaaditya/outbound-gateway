package com.nantaaditya.example.repository;

import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface OutboundResilienceConfigurationRepository
    extends ReactiveCrudRepository<OutboundResilienceConfiguration, Long> {

  Mono<OutboundResilienceConfiguration> findByOutboundSystemId(long outboundSystemId);

  Mono<Void> deleteByOutboundSystemId(long outboundSystemId);
}
