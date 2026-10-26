package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.reactive.ReactorHelper;
import com.nantaaditya.example.reactive.SchedulerHelper;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.service.OutboundHistoryService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class OutboundHistoryServiceImpl implements OutboundHistoryService {

  private final OutboundHistoryRepository historyRepository;
  private final ReactorHelper reactorHelper;
  private final SchedulerHelper schedulerHelper;

  @Override
  public Mono<Boolean> remove(int days) {
    return Mono.just(Boolean.TRUE)
        .doOnNext(result -> reactorHelper.runBackgroundTask(
            "remove_obsolete_outbound_history",
            // thenReturn: ReactorHelper only logs success when the task emits a value
            () -> historyRepository.deleteByCreatedDateBefore(LocalDateTime.now().minusDays(days))
                .thenReturn(Boolean.TRUE),
            schedulerHelper.from("default-async")
        ));
  }
}
