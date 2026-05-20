package com.nantaaditya.example.repository;

import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface OutboundResponseMapperConfigurationRepository
    extends ReactiveCrudRepository<OutboundResponseMapperConfiguration, Long> {

  Mono<OutboundResponseMapperConfiguration> findByOutboundSystemIdAndActiveTrue(
      long outboundSystemId);

  Mono<OutboundResponseMapperConfiguration> findByOutboundSystemId(long outboundSystemId);

  Mono<Void> deleteByOutboundSystemId(long outboundSystemId);
}
