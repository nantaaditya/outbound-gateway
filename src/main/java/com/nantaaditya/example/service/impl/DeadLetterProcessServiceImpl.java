package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.helper.ReactorHelper;
import com.nantaaditya.example.helper.RetryProcessorHelper;
import com.nantaaditya.example.helper.SchedulerHelper;
import com.nantaaditya.example.model.request.RetryDeadLetterProcessRequest;
import com.nantaaditya.example.persistence.CustomQueryHelper;
import com.nantaaditya.example.repository.DeadLetterProcessRepository;
import com.nantaaditya.example.service.DeadLetterProcessService;
import com.nantaaditya.example.service.abstraction.AbstractRetryProcessorService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class DeadLetterProcessServiceImpl implements DeadLetterProcessService {

  private final DeadLetterProcessRepository deadLetterProcessRepository;
  private final CustomQueryHelper customQueryHelper;
  private final ReactorHelper reactorHelper;
  private final RetryProcessorHelper retryProcessorHelper;
  private final SchedulerHelper schedulerHelper;

  @Override
  public Mono<Boolean> remove(int days) {
    return Mono.just(Boolean.TRUE)
      .doOnNext(result -> reactorHelper.runBackgroundTask(
        "remove_obsolete_dead_letter_process",
        () -> deadLetterProcessRepository.deleteByCreatedDateBeforeAndProcessedIsTrue(
            LocalDateTime.now().minusDays(days)
        ),
        schedulerHelper.from("default-async")
      ));
  }

  @Override
  public Mono<Boolean> retry(RetryDeadLetterProcessRequest request) {
    return Mono.just(Boolean.TRUE)
      .doOnNext(result -> reactorHelper.runBackgroundTask(
        "retry_dead_letter_process",
        () -> customQueryHelper.findUnprocessedDeadLetterProcesses(request.processType(), request.processName(), request.size())
          .collectList()
          .doOnNext(deadLetterProcesses -> {
            AbstractRetryProcessorService processor = retryProcessorHelper.getProcessor(request.processType(), request.processName());
            processor.execute(deadLetterProcesses);
          }),
          schedulerHelper.from("default-async")
      ));
  }
}
