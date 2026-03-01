package com.nantaaditya.example.factory;

import org.springframework.web.reactive.function.client.WebClient;

public interface WebClientFactory {
  WebClient of(String name);
}
