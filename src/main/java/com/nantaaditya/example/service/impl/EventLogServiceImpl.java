package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.reactive.ReactorHelper;
import com.nantaaditya.example.reactive.SchedulerHelper;
import com.nantaaditya.example.repository.EventLogRepository;
import com.nantaaditya.example.service.EventLogService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class EventLogServiceImpl implements EventLogService {

  private final EventLogRepository eventLogRepository;
  private final ReactorHelper reactorHelper;
  private final SchedulerHelper schedulerHelper;

  @Override
  public Mono<Boolean> remove(int days) {
    return Mono.just(Boolean.TRUE)
        .doOnNext(result -> reactorHelper.runBackgroundTask(
            "remove_obsolete_event_log",
            () -> eventLogRepository.deleteByCreatedDateBefore(LocalDateTime.now().minusDays(days)),
            schedulerHelper.from("default-async")
            )
        );
  }
}
