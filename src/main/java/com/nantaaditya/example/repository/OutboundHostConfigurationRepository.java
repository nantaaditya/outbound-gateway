package com.nantaaditya.example.repository;

import com.nantaaditya.example.entity.OutboundHostConfiguration;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface OutboundHostConfigurationRepository extends ReactiveCrudRepository<OutboundHostConfiguration, Long> {
  Mono<OutboundHostConfiguration> findByBaseUrl(String baseUrl);
}
