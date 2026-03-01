package com.nantaaditya.example.configuration;

import com.nantaaditya.example.factory.impl.RetryProcessorHelperFactoryBean;
import com.nantaaditya.example.service.abstraction.AbstractRetryProcessorService;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@RequiredArgsConstructor
@Configuration
public class RetryProcessorConfiguration {

  private final ApplicationContext applicationContext;

  @Bean
  public RetryProcessorHelperFactoryBean retryProcessorHelperFactory() {
    RetryProcessorHelperFactoryBean factory = new RetryProcessorHelperFactoryBean();

    Map<String, AbstractRetryProcessorService> processors = applicationContext.getBeansOfType(AbstractRetryProcessorService.class)
      .entrySet()
      .stream()
        .collect(Collectors.toMap(
            e -> e.getValue().getProcessType() + "|" + e.getValue().getProcessName(),
            Entry::getValue)
        );

    factory.setRetryProcessorServices(processors);
    return factory;
  }


}
