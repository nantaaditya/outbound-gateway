package com.nantaaditya.example.model.request;

import java.util.function.Function;
import reactor.core.publisher.Mono;

public record RetryRequest<S, T, E extends Throwable>(
    String processType,
    String processName,
    Function<S, Mono<T>> action,
    Function<E, Mono<T>> fallback,
    S request,
    boolean saveOnMaxRetry
) {

}
