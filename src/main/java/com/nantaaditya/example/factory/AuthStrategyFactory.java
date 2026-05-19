package com.nantaaditya.example.factory;

import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.service.AuthStrategy;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class AuthStrategyFactory {

  private final Map<AuthType, AuthStrategy> strategies;

  public AuthStrategyFactory(List<AuthStrategy> strategyList) {
    this.strategies = strategyList.stream()
        .collect(Collectors.toMap(AuthStrategy::supports, Function.identity()));
  }

  public AuthStrategy getStrategy(AuthType authType) {
    AuthStrategy strategy = strategies.get(authType != null ? authType : AuthType.NONE);
    if (strategy == null) {
      return strategies.get(AuthType.NONE);
    }
    return strategy;
  }
}
