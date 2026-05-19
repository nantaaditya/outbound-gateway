package com.nantaaditya.example.repository;

import com.nantaaditya.example.entity.OutboundHeaderConfiguration;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface OutboundHeaderConfigurationRepository
    extends ReactiveCrudRepository<OutboundHeaderConfiguration, Long> {

  Flux<OutboundHeaderConfiguration> findByOutboundSystemId(long outboundSystemId);
}
