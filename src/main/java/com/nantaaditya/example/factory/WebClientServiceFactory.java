package com.nantaaditya.example.factory;

import com.nantaaditya.example.service.WebClientService;

public interface WebClientServiceFactory {
  WebClientService of(String name);
}
