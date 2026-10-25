package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.entity.OutboundAuthConfiguration;
import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundResilienceConfiguration;
import com.nantaaditya.example.entity.OutboundResponseMapperConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.gateway.OutboundRequestExecutor;
import com.nantaaditya.example.gateway.ResilienceDecorator;
import com.nantaaditya.example.gateway.SystemContextLoader;
import com.nantaaditya.example.helper.GatewayHelper;
import com.nantaaditya.example.helper.JsltResponseMapper;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.helper.OutboundMetricsRecorder;
import com.nantaaditya.example.helper.ParameterResolver;
import com.nantaaditya.example.idempotency.IdempotencyGuard;
import com.nantaaditya.example.model.constant.AuthType;
import com.nantaaditya.example.model.constant.ConverterType;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.dto.ResolvedParameters;
import com.nantaaditya.example.model.exception.InvalidParameterException;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.response.GatewayResponse;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import com.nantaaditya.example.service.impl.GatewayServiceImpl;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GatewayServiceImplTest {

  @Mock
  SystemContextLoader contextLoader;
  @Mock
  OutboundHistoryRepository historyRepository;
  @Mock
  JsltResponseMapper jsltResponseMapper;
  @Mock
  JsonHelper jsonHelper;
  @Mock
  OutboundMetricsRecorder metricsRecorder;
  @Mock
  ParameterResolver parameterResolver;
  @Mock
  IdempotencyGuard idempotencyGuard;
  @Mock
  OutboundRequestExecutor requestExecutor;

  GatewayServiceImpl gatewayService;

  @BeforeEach
  void setUp() {
    // Not @InjectMocks: GatewayServiceImpl needs a real GatewayHelper (it just delegates to
    // jsonHelper/jsltResponseMapper, both mocked above) and a real, stateless ResilienceDecorator,
    // rather than Mockito silently injecting null for unmocked constructor args.
    gatewayService = new GatewayServiceImpl(
        contextLoader, historyRepository, jsonHelper, metricsRecorder,
        new GatewayHelper(jsonHelper, jsltResponseMapper),
        parameterResolver, idempotencyGuard, new ResilienceDecorator(), requestExecutor);
    lenient().when(parameterResolver.resolve(any(), any()))
        .thenReturn(Mono.just(ResolvedParameters.empty()));
    lenient().when(historyRepository.saveHistory(any(OutboundHistory.class))).thenReturn(Mono.just(1));
  }

  @Test
  @DisplayName("evictContext is delegated to the context loader")
  void evictContext_delegatesToLoader() {
    gatewayService.evictContext("some-code");

    verify(contextLoader).evictContext("some-code");
  }

  @Test
  @DisplayName("evictHost is delegated to the context loader")
  void evictHost_delegatesToLoader() {
    gatewayService.evictHost("https://api.example.com");

    verify(contextLoader).evictHost("https://api.example.com");
  }

  @Test
  @DisplayName("a call without an idempotency key runs the executor, saves one history row and never uses the guard")
  void execute_noIdempotencyKey_runsExecutorAndSavesHistory() {
    OutboundSystemContext context = stubContext("no-key-gw", 24, OutboundResilienceConfiguration.bypass(), null);
    executorSucceeds(context);

    GatewayResponse response = gatewayService
        .execute("no-key-gw", "svc", null, new GatewayRequest(Map.of(), null)).block();

    assertThat(response.status()).isEqualTo(200);
    ArgumentCaptor<OutboundHistory> history = ArgumentCaptor.forClass(OutboundHistory.class);
    verify(historyRepository).saveHistory(history.capture());
    assertThat(history.getValue().getRetryCount()).isZero();
    assertThat(history.getValue().getCallerService()).isEqualTo("svc");
    verify(idempotencyGuard, never()).guard(anyLong(), anyString(), anyInt(), any());
  }

  @Test
  @DisplayName("the number of executor subscriptions becomes the retry count saved in the history (attempts - 1)")
  void execute_retriedCall_savesRetryCount() {
    Retry retry = Retry.of("r", RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1)).build());
    OutboundSystemContext context = stubContext("retry-gw", 24,
        OutboundResilienceConfiguration.builder().readTimeOutMs(1000).build(), retry);
    AtomicInteger calls = new AtomicInteger();
    when(requestExecutor.execute(eq(context), any(), any(), any())).thenAnswer(inv -> {
      Runnable onAttempt = inv.getArgument(3);
      return Mono.defer(() -> {
        onAttempt.run();
        return calls.incrementAndGet() < 3
            ? Mono.error(new IllegalStateException("transient"))
            : Mono.just(GatewayResponse.passThrough(200, Map.of(), "{}"));
      });
    });

    GatewayResponse response = gatewayService
        .execute("retry-gw", "svc", null, new GatewayRequest(Map.of(), null)).block();

    assertThat(response.status()).isEqualTo(200);
    ArgumentCaptor<OutboundHistory> history = ArgumentCaptor.forClass(OutboundHistory.class);
    verify(historyRepository).saveHistory(history.capture());
    assertThat(history.getValue().getRetryCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("an error from the call is turned into an internal reject and still saved in the history")
  void execute_executorError_becomesInternalRejectAndIsSaved() {
    OutboundSystemContext context = stubContext("fail-gw", 24, OutboundResilienceConfiguration.bypass(), null);
    when(requestExecutor.execute(eq(context), any(), any(), any()))
        .thenReturn(Mono.error(new IllegalStateException("connection refused")));

    GatewayResponse response = gatewayService
        .execute("fail-gw", "svc", null, new GatewayRequest(Map.of(), null)).block();

    assertThat(response.status()).isGreaterThanOrEqualTo(500);
    verify(historyRepository).saveHistory(any(OutboundHistory.class));
  }

  @Test
  @DisplayName("a failure to save the history is swallowed: the caller still gets the response")
  void execute_historySaveFails_responseStillReturned() {
    OutboundSystemContext context = stubContext("hist-gw", 24, OutboundResilienceConfiguration.bypass(), null);
    executorSucceeds(context);
    when(historyRepository.saveHistory(any(OutboundHistory.class)))
        .thenReturn(Mono.error(new IllegalStateException("db down")));

    GatewayResponse response = gatewayService
        .execute("hist-gw", "svc", null, new GatewayRequest(Map.of(), null)).block();

    assertThat(response.status()).isEqualTo(200);
  }

  @Test
  @DisplayName("an unresolvable injectKey is a client error: nothing is executed and no history is saved")
  void execute_parameterResolutionFails_noExecutionNoHistory() {
    stubContext("param-gw", 24, OutboundResilienceConfiguration.bypass(), null);
    when(parameterResolver.resolve(any(), any()))
        .thenReturn(Mono.error(new InvalidParameterException("injectKeys", "bad")));

    StepVerifier.create(gatewayService.execute("param-gw", "svc", null, new GatewayRequest(Map.of(), null)))
        .expectError(InvalidParameterException.class)
        .verify();

    verify(requestExecutor, never()).execute(any(), any(), any(), any());
    verify(historyRepository, never()).saveHistory(any(OutboundHistory.class));
  }

  @Test
  @DisplayName("CURRENT BEHAVIOUR: an executor that throws while assembling the call escapes the reject conversion, no history")
  void execute_executorThrowsEagerly_escapesWithoutHistory() {
    OutboundSystemContext context = stubContext("eager-gw", 24, OutboundResilienceConfiguration.bypass(), null);
    when(requestExecutor.execute(eq(context), any(), any(), any()))
        .thenThrow(new InvalidParameterException("injectKeys", "request body is required"));

    // Tracked in docs/plans/architecture-remediation.md (Phase 7): it should become a reject + history row.
    StepVerifier.create(gatewayService.execute("eager-gw", "svc", null, new GatewayRequest(Map.of(), null)))
        .expectError(InvalidParameterException.class)
        .verify();

    verify(historyRepository, never()).saveHistory(any(OutboundHistory.class));
  }

  @Test
  @DisplayName("a call with an idempotency key goes through the guard with the system's id and TTL")
  void execute_withIdempotencyKey_goesThroughGuard() {
    stubContext("idem-gw", 12, OutboundResilienceConfiguration.bypass(), null);
    when(idempotencyGuard.guard(eq(1L), eq("key-1"), eq(12), any()))
        .thenReturn(Mono.just(GatewayResponse.passThrough(200, Map.of(), "{}")));

    GatewayResponse response = gatewayService
        .execute("idem-gw", "svc", "key-1", new GatewayRequest(Map.of(), null)).block();

    assertThat(response.fromCache()).isFalse();
    verify(idempotencyGuard).guard(eq(1L), eq("key-1"), eq(12), any());
    verify(metricsRecorder, never()).recordIdempotencyHit("idem-gw");
  }

  @Test
  @DisplayName("a result served from a previous execution is counted as an idempotency hit")
  void execute_guardReplays_recordsIdempotencyHit() {
    stubContext("hit-gw", 24, OutboundResilienceConfiguration.bypass(), null);
    when(idempotencyGuard.guard(eq(1L), eq("key-2"), eq(24), any()))
        .thenReturn(Mono.just(GatewayResponse.fromCache(200, Map.of(), "{}", 200, "{}")));

    GatewayResponse response = gatewayService
        .execute("hit-gw", "svc", "key-2", new GatewayRequest(Map.of(), null)).block();

    assertThat(response.fromCache()).isTrue();
    verify(metricsRecorder).recordIdempotencyHit("hit-gw");
  }

  @Test
  @DisplayName("a blank idempotency key is treated as no key")
  void execute_blankIdempotencyKey_bypassesGuard() {
    OutboundSystemContext context = stubContext("blank-gw", 24, OutboundResilienceConfiguration.bypass(), null);
    executorSucceeds(context);

    gatewayService.execute("blank-gw", "svc", "  ", new GatewayRequest(Map.of(), null)).block();

    verify(idempotencyGuard, never()).guard(anyLong(), anyString(), anyInt(), any());
  }

  // --- helpers ---

  private void executorSucceeds(OutboundSystemContext context) {
    when(requestExecutor.execute(eq(context), any(), any(), any())).thenAnswer(inv -> {
      Runnable onAttempt = inv.getArgument(3);
      return Mono.fromSupplier(() -> {
        onAttempt.run();
        return GatewayResponse.passThrough(200, Map.of(), "{}");
      });
    });
  }

  private OutboundSystemContext stubContext(String code, int ttlHours,
      OutboundResilienceConfiguration resilience, Retry retry) {
    OutboundSystem system = OutboundSystem.builder()
        .id(1L).code(code).httpMethod("POST").hostConfigurationId(1L).apiPath("/path")
        .idempotencyTtlHours(ttlHours).build();
    OutboundSystemContext context = OutboundSystemContext.builder()
        .system(system)
        .hostConfig(OutboundHostConfiguration.builder().id(1L).baseUrl("http://host").build())
        .resilience(resilience)
        .retry(retry)
        .auth(OutboundAuthConfiguration.builder().authType(AuthType.NONE).build())
        .responseMapper(OutboundResponseMapperConfiguration.builder()
            .converterType(ConverterType.NONE).build())
        .headers(List.of())
        .build();
    when(contextLoader.load(code)).thenReturn(Mono.just(context));
    return context;
  }
}
