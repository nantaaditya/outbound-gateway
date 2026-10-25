package com.nantaaditya.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nantaaditya.example.entity.OutboundHistory;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.entity.OutboundSystem;
import com.nantaaditya.example.helper.GatewayHelper;
import com.nantaaditya.example.helper.JsltResponseMapper;
import com.nantaaditya.example.helper.JsonHelper;
import com.nantaaditya.example.model.dto.ContextDTO;
import com.nantaaditya.example.model.dto.OutboundSystemContext;
import com.nantaaditya.example.model.request.GatewayRequest;
import com.nantaaditya.example.model.response.GatewayResponse;
import com.nantaaditya.example.repository.OutboundHistoryRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class HistoryRecorderTest {

  private final OutboundHistoryRepository historyRepository = mock(OutboundHistoryRepository.class);
  private final JsonHelper jsonHelper = mock(JsonHelper.class);

  private HistoryRecorder recorder;

  @BeforeEach
  void setUp() {
    recorder = new HistoryRecorder(historyRepository, jsonHelper,
        new GatewayHelper(jsonHelper, mock(JsltResponseMapper.class)));
    when(historyRepository.saveHistory(any(OutboundHistory.class))).thenReturn(Mono.just(1));
  }

  @Test
  @DisplayName("saves one history row with the call's system, caller, key, original payload and the response")
  void record_savesCallDetails() {
    when(jsonHelper.toJsonString(Map.of("amount", 100))).thenReturn("{\"amount\":100}");
    GatewayResponse response = GatewayResponse.mapped(
        201, Map.of("X-Up", List.of("u")), "{\"id\":1}", 200, "{\"mapped\":1}");

    StepVerifier.create(recorder.record(context(), "billing-svc", "key-1",
            new GatewayRequest(Map.of(), Map.of("amount", 100)), response, 150, 2))
        .verifyComplete();

    OutboundHistory saved = savedHistory();
    assertThat(saved.getOutboundSystemId()).isEqualTo(1L);
    assertThat(saved.getCallerService()).isEqualTo("billing-svc");
    assertThat(saved.getIdempotencyKey()).isEqualTo("key-1");
    assertThat(saved.getRequestMethod()).isEqualTo("POST");
    assertThat(saved.getRequestUrl()).isEqualTo("http://host/v1/pay");
    assertThat(saved.getRequestPayload()).isEqualTo("{\"amount\":100}");
    assertThat(saved.getResponseStatus()).isEqualTo(201);
    assertThat(saved.getResponsePayload()).isEqualTo("{\"id\":1}");
    assertThat(saved.getConvertedStatus()).isEqualTo(200);
    assertThat(saved.getConvertedResponse()).isEqualTo("{\"mapped\":1}");
    assertThat(saved.getDurationMs()).isEqualTo(150);
    assertThat(saved.getRetryCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("the request id comes from the Reactor context of the call")
  void record_takesRequestIdFromReactorContext() {
    ContextDTO callContext = new ContextDTO();
    callContext.setRequestId("req-77");

    recorder.record(context(), "svc", null, new GatewayRequest(Map.of(), null),
            GatewayResponse.passThrough(200, Map.of(), "{}"), 10, 0)
        .contextWrite(Context.of("context", callContext))
        .block();

    assertThat(savedHistory().getRequestId()).isEqualTo("req-77");
  }

  @Test
  @DisplayName("without a Reactor context the request id is simply absent")
  void record_noReactorContext_requestIdIsNull() {
    recorder.record(context(), "svc", null, new GatewayRequest(Map.of(), null),
        GatewayResponse.passThrough(200, Map.of(), "{}"), 10, 0).block();

    assertThat(savedHistory().getRequestId()).isNull();
  }

  @Test
  @DisplayName("a failure to save is logged and swallowed: the returned Mono completes normally")
  void record_saveFails_completesWithoutError() {
    when(historyRepository.saveHistory(any(OutboundHistory.class)))
        .thenReturn(Mono.error(new IllegalStateException("db down")));

    StepVerifier.create(recorder.record(context(), "svc", null, new GatewayRequest(Map.of(), null),
            GatewayResponse.passThrough(200, Map.of(), "{}"), 10, 0))
        .verifyComplete();
  }

  @Test
  @DisplayName("a failure while building the row is swallowed too, and nothing is saved")
  void record_serializationFails_completesWithoutSaving() {
    when(jsonHelper.toJsonString(any())).thenThrow(new IllegalStateException("cannot serialize"));

    StepVerifier.create(recorder.record(context(), "svc", null, new GatewayRequest(Map.of(), Map.of("a", 1)),
            GatewayResponse.passThrough(200, Map.of(), "{}"), 10, 0))
        .verifyComplete();

    verify(historyRepository, never()).saveHistory(any(OutboundHistory.class));
  }

  // --- helpers ---

  private OutboundHistory savedHistory() {
    ArgumentCaptor<OutboundHistory> captor = ArgumentCaptor.forClass(OutboundHistory.class);
    verify(historyRepository).saveHistory(captor.capture());
    return captor.getValue();
  }

  private static OutboundSystemContext context() {
    return OutboundSystemContext.builder()
        .system(OutboundSystem.builder().id(1L).code("pay").httpMethod("POST").apiPath("/v1/pay")
            .hostConfigurationId(1L).build())
        .hostConfig(OutboundHostConfiguration.builder().id(1L).baseUrl("http://host").build())
        .headers(List.of())
        .build();
  }
}
