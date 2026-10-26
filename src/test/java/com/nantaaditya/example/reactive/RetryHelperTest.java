package com.nantaaditya.example.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.constant.RetryType;
import com.nantaaditya.example.model.request.RetryRequest;
import com.nantaaditya.example.observability.TracerHelper;
import com.nantaaditya.example.properties.RetryProperties;
import com.nantaaditya.example.properties.embedded.RetryConfiguration;
import com.nantaaditya.example.repository.DeadLetterProcessRepository;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class RetryHelperTest {

  @Mock
  private ReactorEventBusHelper reactorEventBusHelper;
  @Mock
  private DeadLetterProcessRepository deadLetterProcessRepository;
  @Mock
  private TracerHelper tracerHelper;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("onStart with null configurations is a no-op — no retries are registered")
  void onStart_nullConfigurations_registersNoRetry() {
    var retryHelper = helper(new RetryProperties(null));

    retryHelper.onStart();

    assertThatThrownBy(() -> retryHelper.getRetry("any"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("onStart registers a BACKOFF retry for each configured key")
  void onStart_backoffConfig_registersRetry() {
    var retryHelper = helper(new RetryProperties(Map.of("my-retry", backoffCfg(3, 10))));

    retryHelper.onStart();

    assertThat(retryHelper.getRetry("my-retry")).isNotNull();
  }

  @Test
  @DisplayName("onStart registers a FIXED_DELAY retry for each configured key")
  void onStart_fixedDelayConfig_registersRetry() {
    var retryHelper = helper(new RetryProperties(Map.of("fixed-retry", fixedDelayCfg(2, 10))));

    retryHelper.onStart();

    assertThat(retryHelper.getRetry("fixed-retry")).isNotNull();
  }

  @Test
  @DisplayName("getRetry throws IllegalStateException for an unknown key")
  void getRetry_unknownKey_throwsIllegalState() {
    var retryHelper = helper(new RetryProperties(Map.of()));
    retryHelper.onStart();

    assertThatThrownBy(() -> retryHelper.getRetry("nonexistent"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nonexistent");
  }

  @Test
  @DisplayName("execute happy path returns the action result without retrying")
  void execute_happyPath_returnsResult() {
    when(tracerHelper.getBaggage(any(HeaderConstant.class))).thenReturn("test-req-id");
    var retryHelper = helper(new RetryProperties(Map.of("proc", backoffCfg(3, 10))));
    retryHelper.onStart();

    RetryRequest<String, String, RuntimeException> req = new RetryRequest<>(
        "type", "proc",
        s -> Mono.just(s.toUpperCase()),
        e -> Mono.just("fallback"),
        "hello",
        false
    );

    StepVerifier.create(retryHelper.execute(req))
        .expectNext("HELLO")
        .verifyComplete();
  }

  @Test
  @DisplayName("execute invokes the fallback after retries are exhausted")
  void execute_exhaustedRetry_invokesFallback() {
    when(tracerHelper.getBaggage(any(HeaderConstant.class))).thenReturn("test-req-id");
    var retryHelper = helper(new RetryProperties(Map.of("fail-proc", fixedDelayCfg(1, 10))));
    retryHelper.onStart();

    RetryRequest<String, String, RuntimeException> req = new RetryRequest<>(
        "type", "fail-proc",
        s -> Mono.error(new RuntimeException("always-fail")),
        e -> Mono.just("recovered"),
        "input",
        false
    );

    StepVerifier.create(retryHelper.execute(req))
        .expectNext("recovered")
        .verifyComplete();
  }

  private RetryHelper helper(RetryProperties props) {
    return new RetryHelper(reactorEventBusHelper, props, deadLetterProcessRepository, tracerHelper, objectMapper);
  }

  private RetryConfiguration backoffCfg(long maxAttempt, int backoffMs) {
    return new RetryConfiguration(RetryType.BACKOFF, maxAttempt, backoffMs, TimeUnit.MILLISECONDS, 0, TimeUnit.MILLISECONDS, null);
  }

  private RetryConfiguration fixedDelayCfg(long maxAttempt, int delayMs) {
    return new RetryConfiguration(RetryType.FIXED_DELAY, maxAttempt, 0, TimeUnit.MILLISECONDS, delayMs, TimeUnit.MILLISECONDS, null);
  }
}
