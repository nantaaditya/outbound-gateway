package com.nantaaditya.example.repository;

import com.nantaaditya.example.entity.DeadLetterProcess;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Repository
public interface DeadLetterProcessRepository extends ReactiveCrudRepository<DeadLetterProcess, Long> {

  @Transactional
  Mono<Void> deleteByCreatedDateLessThanAndProcessedIsTrue(long timestamp);
}
