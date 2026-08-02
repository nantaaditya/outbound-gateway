package com.nantaaditya.example.repository;

import com.nantaaditya.example.entity.OutboundSystem;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface OutboundSystemRepository extends ReactiveCrudRepository<OutboundSystem, Long> {

  Mono<OutboundSystem> findByCode(String code);

  Mono<OutboundSystem> findByCodeAndActiveTrue(String code);

  Flux<OutboundSystem> findByHostConfigurationId(Long hostConfigurationId);
}
