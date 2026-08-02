package com.nantaaditya.example.helper;

import com.nantaaditya.example.model.constant.ConstantName;
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

    FeatureConstant.find(contextDTO.getMethod(), contextDTO.getPath())
        .ifPresent(f -> observationContext.addLowCardinalityKeyValue(KeyValue.of(ConstantName.FEATURE_TAG_OBSERVABILITY, f.name())));

    if (contextDTO.getRequestId() != null) {
      observationContext.addHighCardinalityKeyValue(KeyValue.of(ConstantName.REQUEST_ID_TAG_OBSERVABILITY, contextDTO.getRequestId()));
    }

    return observationContext;
  }

}
