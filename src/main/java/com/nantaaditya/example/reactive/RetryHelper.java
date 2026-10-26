package com.nantaaditya.example.reactive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nantaaditya.example.entity.DeadLetterProcess;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.constant.RetryConstant;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.request.RetryRequest;
import com.nantaaditya.example.observability.TracerHelper;
import com.nantaaditya.example.properties.RetryProperties;
import com.nantaaditya.example.properties.embedded.RetryConfiguration;
import com.nantaaditya.example.repository.DeadLetterProcessRepository;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

@Log4j2
@RequiredArgsConstructor
public class RetryHelper {

  private final ReactorEventBusHelper reactorEventBusHelper;
  private final RetryProperties retryProperties;
  private final DeadLetterProcessRepository deadLetterProcessRepository;
  private final TracerHelper tracerHelper;
  private final ObjectMapper objectMapper;

  public static final String BEFORE_SUFFIX_EVENT = "BeforeEvent";
  public static final String AFTER_SUFFIX_EVENT = "AfterEvent";

  private Map<String, Retry> retries = new ConcurrentHashMap<>();

  @EventListener(ApplicationReadyEvent.class)
  public void onStart() {
    if (retryProperties.configurations() == null) {
      return;
    }

    retryProperties.configurations()
      .forEach((key, value) -> {
        RetryBackoffSpec retry = createRetry(value);

        if (!value.getRetryableExceptions().isEmpty()) {
          retry = retry
            .filter(exception -> isRetryable(exception, value.getRetryableExceptions()))
            .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
              log.error(AppLogMessage
                  .message("#Retry - exhausted retry {} error {}", key, retrySignal.failure().getMessage())
                  .error(retrySignal.failure())
              );
              throw new IllegalStateException("#Retry - exhausted retry " + key, retrySignal.failure());
            });
        }

        retry = retry
          .doBeforeRetry(retrySignal ->
            reactorEventBusHelper.publish(getRetryEvent(key, BEFORE_SUFFIX_EVENT), retrySignal)
          )
          .doAfterRetry(retrySignal ->
            reactorEventBusHelper.publish(getRetryEvent(key, AFTER_SUFFIX_EVENT), retrySignal)
          );

        retries.put(key, retry);
      }
    );
  }

  public <S, T, E extends Throwable> Mono<T> execute(RetryRequest<S, T, E> retryRequest) {
    return Mono.defer(() -> retryRequest.action().apply(retryRequest.request()))
        .retryWhen(getRetry(retryRequest.processName()))
        .onErrorResume(error -> retryRequest.fallback().apply((E) error)
            .doOnNext(item -> saveOnMaxRetry(retryRequest, error))
        )
        .contextWrite(context -> updateContext(retryRequest, context));
  }

  public Retry getRetry(String key) {
    Retry retry = retries.get(key);
    if (retry == null) {
      throw new IllegalStateException("#Retry - no retry " + key);
    }
    return retry;
  }

  private RetryBackoffSpec createRetry(RetryConfiguration configuration) {
    return switch (configuration.type()) {
      case BACKOFF -> Retry.backoff(
        configuration.maxAttempt(),
        Duration.of(
          configuration.backoffTime(),
          toTemporalUnit(configuration.backoffTimeUnit())
        )
      );
      case FIXED_DELAY -> Retry.fixedDelay(
        configuration.maxAttempt(),
        Duration.of(
          configuration.fixedDelayTime(),
          toTemporalUnit(configuration.fixedDelayTimeUnit())
        )
      );
    };
  }

  private boolean isRetryable(Throwable throwable, Map<Class<? extends Throwable>, Boolean> retryableMap) {
    for (Map.Entry<Class<? extends Throwable>, Boolean> entry : retryableMap.entrySet()) {
      if (entry.getKey().isAssignableFrom(throwable.getClass())) {
        return entry.getValue();
      }
    }
    return false;
  }

  private TemporalUnit toTemporalUnit(TimeUnit timeUnit) {
    return switch (timeUnit) {
      case NANOSECONDS -> ChronoUnit.NANOS;
      case MICROSECONDS -> ChronoUnit.MICROS;
      case MILLISECONDS -> ChronoUnit.MILLIS;
      case SECONDS -> ChronoUnit.SECONDS;
      case MINUTES -> ChronoUnit.MINUTES;
      case HOURS -> ChronoUnit.HOURS;
      case DAYS -> ChronoUnit.DAYS;
    };
  }

  private String getRetryEvent(String key, String suffix) {
    return key + suffix;
  }

  private <S, T, E extends Throwable> void saveOnMaxRetry(RetryRequest<S, T, E> retryRequest,
      Throwable throwable) {
    if (retryRequest.saveOnMaxRetry()) {
      deadLetterProcessRepository.save(DeadLetterProcess.create(retryRequest, throwable, objectMapper))
          .subscribe(
              success -> log.debug(AppLogMessage.message("#Retry - success save to dead_letter_process on {} - {}",
                  retryRequest.processType(), retryRequest.processName())),
              error -> log.error(AppLogMessage.message("#Retry - failed save to dead_letter_process on {} - {}, error {}",
                  retryRequest.processType(), retryRequest.processName(), error.getMessage()).error(error))
          );
    }
  }

  private <S, T, E extends Throwable> Context updateContext(RetryRequest<S, T, E> retryRequest,
      Context context) {
    return context
        .put(RetryConstant.REQUEST, retryRequest.request())
        .put(RetryConstant.REQUEST_ID, tracerHelper.getBaggage(HeaderConstant.REQUEST_ID))
        .put(RetryConstant.PROCESS_TYPE, retryRequest.processType())
        .put(RetryConstant.PROCESS_NAME, retryRequest.processName());
  }

}
