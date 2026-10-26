package com.nantaaditya.example.reactive;

import com.nantaaditya.example.helper.ErrorHelper;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.constant.JobConstant;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.request.ReactorJobRequest;
import com.nantaaditya.example.observability.TracerHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Log4j2
@Component
@RequiredArgsConstructor
public class ReactorJobExecutor {

  private final TracerHelper tracerHelper;

  public <S, T> Mono<T> execute(ReactorJobRequest<S, T> request) {
    return Mono.using(
        () -> {
          log.info(AppLogMessage.message("#Reactor - job start {} with time out {}", request.source(), request.timeOut()));
          return request.source();
        },
        source -> Mono.fromCallable(() -> source)
            .publishOn(request.scheduler())
            .flatMap(request.callback())
            .timeout(request.timeOut(), request.fallback(), request.scheduler())
            .doOnError(error -> log.error(AppLogMessage.message("#Reactor - job {} got error {}",
                request.source(), error.getMessage()).error(error))
            )
            .contextWrite(context -> createContext(context, request.source()))
            .onErrorResume(error -> Mono.<T>error(error)
                .contextWrite(context -> updateContext(context, error))
            )
        ,
        source -> {
          log.info(AppLogMessage.message("#Reactor - job finish {}", request.source()));
          request.cleanUp().accept(source);
        }
    );
  }

  private <S> Context createContext(Context context, S source) {
    return context
        .put(JobConstant.REQUEST, source)
        .put(JobConstant.REQUEST_ID, tracerHelper.getBaggage(HeaderConstant.REQUEST_ID));
  }

  private Context updateContext(Context context, Throwable throwable) {
    return context
        .put(JobConstant.ERROR_MESSAGE, throwable.getMessage())
        .put(JobConstant.ERROR_ROOT_CAUSE, ErrorHelper.getRootCause(throwable));
  }
}
