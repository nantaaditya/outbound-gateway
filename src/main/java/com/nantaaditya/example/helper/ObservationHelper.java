package com.nantaaditya.example.helper;

import com.nantaaditya.example.model.constant.FeatureConstant;
import com.nantaaditya.example.model.dto.ContextDTO;
import com.nantaaditya.example.model.logger.AppLogMessage;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.Observation.Context;
import io.micrometer.observation.Observation.Event;
import io.micrometer.observation.ObservationRegistry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

@Log4j2
@Getter
@Component
@RequiredArgsConstructor
public class ObservationHelper {

  private final ObservationRegistry observationRegistry;
  private final TracerHelper tracerHelper;
  private final ContextHelper contextHelper;

  public void publishEvent(String key, String value) {
    Observation current = observationRegistry.getCurrentObservation();
    if (current != null) {
      current.event(Event.of(key, value));
    }
  }

  public Context createApiContext(ContextDTO contextDTO) {
    Context observationContext = new Context();
    if (contextDTO == null) {
      log.warn(AppLogMessage.message("#Observation - ContextDTO is null"));
      return observationContext;
    }

    FeatureConstant feature = FeatureConstant.get(contextDTO.getMethod(), contextDTO.getPath());
    if (feature != null) {
      observationContext.addLowCardinalityKeyValue(KeyValue.of("feature", feature.name()));
    }

    if (contextDTO.getRequestId() != null) {
      observationContext.addHighCardinalityKeyValue(KeyValue.of("requestId", contextDTO.getRequestId()));
    }

    return observationContext;
  }

}
