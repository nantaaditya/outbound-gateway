package com.nantaaditya.example.repository;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface OutboundAuthConfigurationRepository
    extends ReactiveCrudRepository<OutboundAuthConfiguration, Long> {

  Mono<OutboundAuthConfiguration> findByOutboundSystemId(long outboundSystemId);

  Mono<Void> deleteByOutboundSystemId(long outboundSystemId);
}
