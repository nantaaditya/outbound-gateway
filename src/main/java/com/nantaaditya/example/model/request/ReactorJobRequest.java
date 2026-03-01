package com.nantaaditya.example.model.request;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

public record ReactorJobRequest<S, T>(
    S source,
    Function<S, Mono<T>> callback,
    Mono<T> fallback,
    Consumer<S> cleanUp,
    Duration timeOut,
    Scheduler scheduler
) {

}
