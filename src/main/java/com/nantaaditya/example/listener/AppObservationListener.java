package com.nantaaditya.example.listener;

import com.nantaaditya.example.model.constant.ObservationConstant;
import com.nantaaditya.example.model.logger.AppLogMessage;
import io.micrometer.observation.Observation.Context;
import io.micrometer.observation.Observation.Event;
import io.micrometer.observation.ObservationHandler;
import java.util.stream.Stream;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class AppObservationListener implements ObservationHandler<Context> {

  @Override
  public boolean supportsContext(Context context) {
    return isEligibleToObserved(context);
  }

  @Override
  public void onStart(Context context) {
    log.info(AppLogMessage.message("#Metrics - start").additionalData(context));
  }

  @Override
  public void onEvent(Event event, Context context) {
    log.info(AppLogMessage.message("#Metrics - event").additionalData(event));
  }

  @Override
  public void onError(Context context) {
    log.error(AppLogMessage.message("#Metrics - error").additionalData(context));
  }

  @Override
  public void onStop(Context context) {
    log.info(AppLogMessage.message("#Metrics - stop").additionalData(context));
  }

  public static boolean isEligibleToObserved(Context context) {
    return Stream.of(ObservationConstant.values())
        .anyMatch(item -> item.getName().equals(context.getName()));
  }
}
