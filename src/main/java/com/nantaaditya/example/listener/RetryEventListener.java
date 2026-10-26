package com.nantaaditya.example.listener;

import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.properties.RetryProperties;
import com.nantaaditya.example.reactive.ReactorEventBusHelper;
import com.nantaaditya.example.reactive.RetryHelper;
import com.nantaaditya.example.reactive.SchedulerHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.util.retry.Retry.RetrySignal;

@Log4j2
@Component
@RequiredArgsConstructor
public class RetryEventListener {

  private final RetryProperties retryProperties;
  private final ReactorEventBusHelper reactorEventBusHelper;
  private final SchedulerHelper schedulerHelper;

  @EventListener(ApplicationReadyEvent.class)
  public void retryEventSubscriber() {
    if (retryProperties.configurations() == null) return;

    for (String retryKey : retryProperties.configurations().keySet()) {
      consumeEvent(retryKey, RetryHelper.BEFORE_SUFFIX_EVENT);
      consumeEvent(retryKey, RetryHelper.AFTER_SUFFIX_EVENT);
    }
  }

  private void consumeEvent(String retryName, String suffixEvent) {
    reactorEventBusHelper.<RetrySignal>consume(retryName + suffixEvent, schedulerHelper.from("retry-listener"))
        .doOnNext(retrySignal -> {
          log.info(AppLogMessage.message(
              "#Retry - name {} event {}", retryName + suffixEvent, retrySignal.totalRetries() + 1));
          retrySignal.retryContextView()
              .forEach((key, value) -> log.info(AppLogMessage.message("#Retry - name {} context {} - {}", retryName, key, value)));
        })
        .subscribe(
            success -> log.debug(AppLogMessage.message("#Retry - success consume {}", retryName + suffixEvent)),
            error -> log.error(AppLogMessage.message("#Retry - failed consume {}, error {}",
                retryName + suffixEvent, error.getMessage()).error(error))
        );
  }

}
