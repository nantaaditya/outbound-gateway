package com.nantaaditya.example.observability;

import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.logger.AppLogMessage;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.BaggageManager;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
public class TracerHelper {

  private final BaggageManager baggageManager;

  public Map<String, String> getBaggages() {
    return baggageManager.getAllBaggage();
  }

  public String getBaggage(HeaderConstant header) {
    return getBaggages().get(header.getHeader());
  }

  public void setBaggage(String key, String value) {
    try {
        Baggage baggage = Optional.ofNullable(baggageManager.getBaggage(key))
            .orElseGet(() -> baggageManager.createBaggage(key)); //NOSONAR
        baggage.makeCurrent(value);
    } catch (Exception e) {
      log.error(AppLogMessage.message("#Baggage - failed to set baggage {} with value {}, error {}",
          key, value, e.getMessage()).error(e));
    }
  }
}
