package com.nantaaditya.example.configuration;

import brave.baggage.BaggageField;
import com.nantaaditya.example.listener.AppObservationListener;
import com.nantaaditya.example.model.constant.HeaderConstant;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservationConfiguration {

  @Bean
  public ObservationRegistry observationRegistry() {
    ObservationRegistry observationRegistry =  ObservationRegistry.create();
    observationRegistry
        .observationConfig()
        .observationHandler(new AppObservationListener());
    return observationRegistry;
  }

  @Bean
  public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
    return new ObservedAspect(observationRegistry);
  }

  @Bean
  public BaggageField clientIdBaggage() {
    return BaggageField.create(HeaderConstant.CLIENT_ID.getHeader());
  }

  @Bean
  public BaggageField requestIdBaggage() {
    return BaggageField.create(HeaderConstant.REQUEST_ID.getHeader());
  }
}
