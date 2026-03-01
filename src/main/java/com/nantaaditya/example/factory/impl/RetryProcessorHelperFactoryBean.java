package com.nantaaditya.example.factory.impl;

import com.nantaaditya.example.helper.RetryProcessorHelper;
import com.nantaaditya.example.service.abstraction.AbstractRetryProcessorService;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.FactoryBean;

@Setter
public class RetryProcessorHelperFactoryBean implements FactoryBean<RetryProcessorHelper> {

  private Map<String, AbstractRetryProcessorService> retryProcessorServices = new HashMap<>();

  @Override
  public RetryProcessorHelper getObject() throws Exception {
    return new RetryProcessorHelperImpl(retryProcessorServices);
  }

  @Override
  public Class<?> getObjectType() {
    return RetryProcessorHelper.class;
  }

  @AllArgsConstructor
  private static class RetryProcessorHelperImpl implements RetryProcessorHelper {
    private final Map<String, AbstractRetryProcessorService> processors;

    public AbstractRetryProcessorService getProcessor(String processType, String processName) {
      return processors.get(getKey(processType, processName));
    }

    private String getKey(String processType, String processName) {
      return processType + "|" + processName;
    }
  }
}