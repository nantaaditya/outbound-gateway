package com.nantaaditya.example.helper;

import com.nantaaditya.example.model.logger.AppLogMessage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Log4j2
@Component
public class ReactorEventBusHelper {

  private static final Map<String, Sinks.Many> SINKS = new ConcurrentHashMap<>(); //NOSONAR
  private static final Lock LOCK = new ReentrantLock(true);
  private static final long TIME_OUT = 5l;
  private static final TimeUnit TIME_OUT_TIME_UNIT = TimeUnit.SECONDS;

  public <T> void createSinks(String name, Sinks.Many<T> sink) {
    try {
      log.info(AppLogMessage.message("#Event - create sinks for {}", name));
      SINKS.put(name, sink);
    } catch (Exception e) {
      log.error(AppLogMessage.message("#Event - create sinks {} error {}", name, e.getMessage()).error(e));
    }
  }

  public <T> Sinks.Many<T> getSinks(String name) {
    return SINKS.get(name);
  }

  public <T> void publish(String name, T event) {
    boolean locked = false;
    try {
      if (name == null || event == null) {
        log.warn(AppLogMessage.message("#Event - event payload is null"));
        return;
      }

      locked = LOCK.tryLock(TIME_OUT, TIME_OUT_TIME_UNIT);

      Sinks.Many<T> sink = SINKS.get(name);
      if (sink == null) {
        log.warn(AppLogMessage.message("#Event - sinks for {} not found", name));
        return;
      }

      EmitResult emitResult = sink.tryEmitNext(event);
      if (emitResult.isFailure()) {
        log.error(AppLogMessage.message("#Event - failed emit event {}, error {}", event, emitResult));
        emitResult.orThrow();
      }
    } catch (InterruptedException ex) {
      log.error(AppLogMessage.message("#Event - interrupted while waiting for sinks for {}, error {} ", name, ex.getCause()).error(ex)); //NOSONAR
      Thread.currentThread().interrupt();
    } finally {
      if (locked) LOCK.unlock();
    }
  }

  public <T> Flux<T> consume(String name, Scheduler scheduler) {
    if (scheduler == null) {
      scheduler = Schedulers.immediate();
    }

    Sinks.Many<T> sink = SINKS.get(name);
    if (sink == null) {
      return Flux.empty();
    }

    return sink.asFlux()
        .doOnNext(event -> log.debug(AppLogMessage.message("#Event - consume sink for {} value {}", name, event)))
        .publishOn(scheduler);
  }
}
