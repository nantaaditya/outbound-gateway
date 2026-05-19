package com.nantaaditya.example.properties.embedded;

public record AsyncConfiguration(
    int corePoolSize,
    int maxPoolSize,
    int queueCapacity,
    int keepAliveSeconds,
    String threadNamePrefix,
    boolean enableVirtualThread
) {

  public int getThrottlerSize() {
    return maxPoolSize + queueCapacity;
  }
}
