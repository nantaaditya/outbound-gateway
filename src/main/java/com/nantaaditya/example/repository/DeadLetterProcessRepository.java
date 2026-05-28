package com.nantaaditya.example.repository;

import com.nantaaditya.example.entity.DeadLetterProcess;
import java.time.LocalDateTime;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface DeadLetterProcessRepository extends ReactiveCrudRepository<DeadLetterProcess, Long> {
  
  Mono<Void> deleteByCreatedDateBeforeAndProcessedIsTrue(LocalDateTime date);
}
