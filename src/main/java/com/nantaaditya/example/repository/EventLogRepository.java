package com.nantaaditya.example.repository;

import com.nantaaditya.example.entity.EventLog;
import java.time.LocalDateTime;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Repository
public interface EventLogRepository extends ReactiveCrudRepository<EventLog, String> {
  @Transactional
  Mono<Void> deleteByCreatedDateBefore(LocalDateTime date);

  Mono<EventLog> findByRequestId(String requestId);
}
