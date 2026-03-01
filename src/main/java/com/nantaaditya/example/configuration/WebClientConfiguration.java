package com.nantaaditya.example.configuration;

import com.nantaaditya.example.factory.WebClientFactory;
import com.nantaaditya.example.factory.impl.WebClientFactoryBean;
import com.nantaaditya.example.factory.impl.WebClientServiceFactoryBean;
import com.nantaaditya.example.properties.ClientProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.zalando.logbook.Logbook;

@Configuration
@EnableConfigurationProperties({
    ClientProperties.class
})
public class WebClientConfiguration {

  @Bean
  public WebClientFactoryBean webClientFactoryBean(
      ClientProperties clientProperties,
      Logbook logbook) {
    return new WebClientFactoryBean(clientProperties, logbook);
  }

  @Bean
  @DependsOn("webClientFactoryBean")
  public WebClientServiceFactoryBean webClientServiceFactoryBean(
      WebClientFactory webClientFactory,
      ClientProperties clientProperties) {
    WebClientServiceFactoryBean webClientServiceFactoryBean = new WebClientServiceFactoryBean();
    webClientServiceFactoryBean.setWebClientFactory(webClientFactory);
    webClientServiceFactoryBean.setClientProperties(clientProperties);
    return webClientServiceFactoryBean;
  }
}
