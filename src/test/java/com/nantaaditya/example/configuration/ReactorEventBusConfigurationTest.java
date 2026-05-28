package com.nantaaditya.example.configuration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.nantaaditya.example.helper.ReactorEventBusHelper;
import com.nantaaditya.example.helper.RetryHelper;
import com.nantaaditya.example.model.constant.RetryType;
import com.nantaaditya.example.properties.ReactorEventProperties;
import com.nantaaditya.example.properties.RetryProperties;
import com.nantaaditya.example.properties.embedded.ReactorEventConfiguration;
import com.nantaaditya.example.properties.embedded.RetryConfiguration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Sinks;

@ExtendWith(MockitoExtension.class)
class ReactorEventBusConfigurationTest {

  @Mock
  private ReactorEventBusHelper reactorEventBusHelper;

  @Test
  @DisplayName("createSinks creates before/after event sinks for each retry configuration key")
  void createSinks_withRetryConfigs_createsBeforeAndAfterSinks() {
    RetryConfiguration cfg = new RetryConfiguration(RetryType.BACKOFF, 3, 100, TimeUnit.MILLISECONDS, 0, TimeUnit.MILLISECONDS, null);
    ReactorEventBusConfiguration config = new ReactorEventBusConfiguration(
        reactorEventBusHelper,
        new ReactorEventProperties(null),
        new RetryProperties(Map.of("order-retry", cfg))
    );

    config.createSinks();

    verify(reactorEventBusHelper).createSinks(eq("order-retry" + RetryHelper.BEFORE_SUFFIX_EVENT), any(Sinks.Many.class));
    verify(reactorEventBusHelper).createSinks(eq("order-retry" + RetryHelper.AFTER_SUFFIX_EVENT), any(Sinks.Many.class));
  }

  @Test
  @DisplayName("createSinks creates a custom event sink for each reactor-event configuration key")
  void createSinks_withEventConfigs_createsEventSinks() {
    ReactorEventBusConfiguration config = new ReactorEventBusConfiguration(
        reactorEventBusHelper,
        new ReactorEventProperties(Map.of("payment-event", new ReactorEventConfiguration(100))),
        new RetryProperties(null)
    );

    config.createSinks();

    verify(reactorEventBusHelper).createSinks(eq("payment-event"), any(Sinks.Many.class));
  }

  @Test
  @DisplayName("createSinks with both null configurations does nothing")
  void createSinks_bothNull_doesNothing() {
    ReactorEventBusConfiguration config = new ReactorEventBusConfiguration(
        reactorEventBusHelper,
        new ReactorEventProperties(null),
        new RetryProperties(null)
    );

    config.createSinks();

    verifyNoMoreInteractions(reactorEventBusHelper);
  }

  @Test
  @DisplayName("createSinks creates sinks for multiple retry keys in a single call")
  void createSinks_multipleRetryKeys_createsAllSinks() {
    RetryConfiguration cfg = new RetryConfiguration(RetryType.FIXED_DELAY, 2, 0, TimeUnit.MILLISECONDS, 50, TimeUnit.MILLISECONDS, null);
    ReactorEventBusConfiguration config = new ReactorEventBusConfiguration(
        reactorEventBusHelper,
        new ReactorEventProperties(null),
        new RetryProperties(Map.of("retry-a", cfg, "retry-b", cfg))
    );

    config.createSinks();

    // 2 retries × 2 sinks each = 4 createSinks calls
    verify(reactorEventBusHelper, times(4)).createSinks(any(), any(Sinks.Many.class));
  }
}
