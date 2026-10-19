package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.helper.ReactorHelper;
import com.nantaaditya.example.helper.SchedulerHelper;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.service.impl.OutboundHistoryServiceImpl;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class OutboundHistoryServiceImplTest {

  @Mock
  private OutboundHistoryRepository historyRepository;
  @Mock
  private ReactorHelper reactorHelper;
  @Mock
  private SchedulerHelper schedulerHelper;
  @Captor
  private ArgumentCaptor<Supplier<Mono<Boolean>>> taskCaptor;
  @Captor
  private ArgumentCaptor<LocalDateTime> cutoffCaptor;

  private OutboundHistoryServiceImpl service;
  private final Scheduler scheduler = Schedulers.immediate();

  @BeforeEach
  void setUp() {
    service = new OutboundHistoryServiceImpl(historyRepository, reactorHelper, schedulerHelper);
    when(schedulerHelper.from("default-async")).thenReturn(scheduler);
  }

  @Test
  @DisplayName("remove returns true immediately and schedules the purge as a background task")
  void remove_schedulesBackgroundTask() {
    StepVerifier.create(service.remove(30))
        .expectNext(Boolean.TRUE)
        .verifyComplete();

    verify(reactorHelper).runBackgroundTask(eq("remove_obsolete_outbound_history"), any(), eq(scheduler));
  }

  @Test
  @DisplayName("the scheduled task deletes histories created before now minus the given days")
  void remove_taskDeletesWithCutoff() {
    when(historyRepository.deleteByCreatedDateBefore(any())).thenReturn(Mono.empty());

    service.remove(30).block();

    verify(reactorHelper).runBackgroundTask(any(), taskCaptor.capture(), any());
    // emits a value so ReactorHelper logs the background task as successful
    StepVerifier.create(taskCaptor.getValue().get())
        .expectNext(Boolean.TRUE)
        .verifyComplete();

    verify(historyRepository).deleteByCreatedDateBefore(cutoffCaptor.capture());
    LocalDateTime expected = LocalDateTime.now().minusDays(30);
    assertThat(Duration.between(cutoffCaptor.getValue(), expected).abs())
        .isLessThan(Duration.ofSeconds(5));
  }
}
