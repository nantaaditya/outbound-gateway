package com.nantaaditya.example.interceptor;

import com.nantaaditya.example.helper.TracerHelper;
import com.nantaaditya.example.model.constant.ClientFeatureConstant;
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.constant.HeaderConstant;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation.Context;
import io.micrometer.observation.ObservationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WebClientObservationFilter implements ObservationFilter {

  private final TracerHelper tracerHelper;

  private static final String CLIENT_CONTEXT_NAME = "http.client.requests";
  private static final String METHOD_CONTEXT_NAME = "method";
  private static final String URI_CONTEXT_NAME = "uri";

  @Override
  public Context map(Context context) {
    return switch (context.getName()) {
      case CLIENT_CONTEXT_NAME -> enrichWebClientContext(context);
      default -> context;
    };
  }

  private Context enrichWebClientContext(Context context) {
    String method = (String) context.get(METHOD_CONTEXT_NAME);
    String path = (String) context.get(URI_CONTEXT_NAME);

    if (method != null && path != null) {
      ClientFeatureConstant feature = ClientFeatureConstant.get(method, path);

      if (feature != null) {
        context.addLowCardinalityKeyValue(KeyValue.of(ConstantName.FEATURE_TAG_OBSERVABILITY, feature.name()));
      }
    }

    String requestId = tracerHelper.getBaggage(HeaderConstant.REQUEST_ID);

    if (requestId != null) {
      context.addHighCardinalityKeyValue(KeyValue.of(ConstantName.REQUEST_ID_TAG_OBSERVABILITY, requestId));
    }

    return context;
  }
}
