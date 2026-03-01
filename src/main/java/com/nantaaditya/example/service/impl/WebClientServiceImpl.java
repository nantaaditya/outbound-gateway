package com.nantaaditya.example.service.impl;

import com.nantaaditya.example.factory.WebClientFactory;
import com.nantaaditya.example.service.WebClientService;
import org.springframework.web.reactive.function.client.WebClient;

public class WebClientServiceImpl implements WebClientService {

  private WebClient webClient;

  public WebClientServiceImpl(
      String webClientName,
      WebClientFactory webClientFactory) {
    this.webClient = webClientFactory.of(webClientName);
  }

  @Override
  public <T> Builder<T> builder() {
    return new DefaultWebClientBuilder<>(webClient);
  }
}
