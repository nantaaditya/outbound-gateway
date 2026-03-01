package com.nantaaditya.example.factory.impl;

import com.nantaaditya.example.factory.WebClientServiceFactory;
import com.nantaaditya.example.service.WebClientService;
import java.util.Map;

public class WebClientServiceFactoryImpl implements WebClientServiceFactory {

  private final Map<String, WebClientService> webClientServices;

  public WebClientServiceFactoryImpl(Map<String, WebClientService> webClientServices) {
    this.webClientServices = webClientServices;
  }

  @Override
  public WebClientService of(String name) {
    return webClientServices.get(name);
  }
}