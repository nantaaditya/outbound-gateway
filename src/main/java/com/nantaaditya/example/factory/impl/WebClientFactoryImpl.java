package com.nantaaditya.example.factory.impl;

import com.nantaaditya.example.factory.WebClientFactory;
import java.util.Map;
import org.springframework.web.reactive.function.client.WebClient;

public class WebClientFactoryImpl implements WebClientFactory {

  private final Map<String, WebClient> clients;

  public WebClientFactoryImpl(Map<String, WebClient> clients) {
    this.clients = clients;
  }

  @Override
  public WebClient of(String name) {
    return clients.get(name);
  }
}
