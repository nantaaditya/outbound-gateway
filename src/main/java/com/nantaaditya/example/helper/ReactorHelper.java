package com.nantaaditya.example.helper;

import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.observability.TracerHelper;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Log4j2
@Component
@RequiredArgsConstructor
public class ReactorHelper {

  private final TracerHelper tracerHelper;

  public <T> void runBackgroundTask(String processName, Supplier<Mono<T>> monoSupplier, Scheduler scheduler) {
    Mono.defer(() ->
        monoSupplier.get()
          .doOnNext(result -> tracerHelper.setBaggage("backgroundTask", processName))
      )
      .subscribeOn(scheduler)
      .subscribe(
          success -> log.info(AppLogMessage.message("#Reactor - run background task {} success", processName)),
          error -> log.error(AppLogMessage.message("#Reactor - run background task {} error, error {}",
              processName, error.getMessage()).error(error))
      );
  }
}
