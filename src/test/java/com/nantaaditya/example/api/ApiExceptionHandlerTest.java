package com.nantaaditya.example.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nantaaditya.example.helper.ContextHelper;
import com.nantaaditya.example.helper.ResponseHelper;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.constant.ResponseCode;
import com.nantaaditya.example.model.exception.HostConfigNotFoundException;
import com.nantaaditya.example.model.exception.OutboundSystemNotFoundException;
import com.nantaaditya.example.model.response.BaseResponse;
import com.nantaaditya.example.observability.ObservationHelper;
import com.nantaaditya.example.observability.TracerHelper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.BadSqlGrammarException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import reactor.core.publisher.Sinks;

@ExtendWith(MockitoExtension.class)
class ApiExceptionHandlerTest {

  @Mock
  private ResponseHelper responseHelper;
  @Mock
  private ContextHelper contextHelper;
  @Mock
  private TracerHelper tracerHelper;
  @Mock
  private ObservationHelper observationHelper;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private ApiExceptionHandler handler;

  @BeforeEach
  void setUp() {
    handler = new ApiExceptionHandler(objectMapper, responseHelper, contextHelper, tracerHelper, observationHelper);
    when(tracerHelper.getBaggage(any(HeaderConstant.class))).thenReturn("test-request-id");
    when(responseHelper.failed(any(ResponseCode.class), any())).thenReturn(new BaseResponse<>());
  }

  @Test
  @DisplayName("noResourceFoundException returns bad-request response with endpoint error")
  void noResourceFoundException_returnsResponse() {
    NoResourceFoundException ex = mock(NoResourceFoundException.class);
    when(ex.getStackTrace()).thenReturn(new StackTraceElement[0]);

    BaseResponse<Object> result = handler.noResourceFoundException(ex);

    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("emissionException returns bad-request response with emit-result reason")
  void emissionException_returnsResponse() {
    Sinks.EmissionException ex = new Sinks.EmissionException(Sinks.EmitResult.FAIL_OVERFLOW);

    BaseResponse<Object> result = handler.emissionException(ex);

    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("sqlException returns internal-error response for bad SQL grammar")
  void sqlException_returnsResponse() {
    BadSqlGrammarException ex = mock(BadSqlGrammarException.class);
    when(ex.getStackTrace()).thenReturn(new StackTraceElement[0]);

    BaseResponse<Object> result = handler.sqlException(ex);

    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("handlerMethodValidationException returns bad-request response with parameter names")
  void handlerMethodValidationException_returnsResponse() {
    HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
    when(ex.getStackTrace()).thenReturn(new StackTraceElement[0]);
    ParameterValidationResult pvr = mock(ParameterValidationResult.class);
    MethodParameter mp = mock(MethodParameter.class);
    Parameter param = mock(Parameter.class);
    when(ex.getParameterValidationResults()).thenReturn(List.of(pvr));
    when(pvr.getMethodParameter()).thenReturn(mp);
    when(mp.getParameter()).thenReturn(param);
    when(param.getName()).thenReturn("requestBody");

    BaseResponse<Object> result = handler.handlerMethodValidationException(ex);

    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("constraintViolationException returns bad-request response with field violations")
  @SuppressWarnings({"unchecked", "rawtypes"})
  void constraintViolationException_returnsResponse() {
    ConstraintViolation<?> violation = mock(ConstraintViolation.class);
    ConstraintDescriptor<?> descriptor = mock(ConstraintDescriptor.class);
    Map<String, Object> attrs = new HashMap<>();
    attrs.put("path", new String[]{"fieldName"});
    when(violation.getConstraintDescriptor()).thenReturn((ConstraintDescriptor) descriptor);
    when(descriptor.getAttributes()).thenReturn(attrs);
    when(violation.getMessage()).thenReturn("must not be null");
    ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

    BaseResponse<Object> result = handler.constraintViolationException(ex);

    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("outboundSystemNotFoundException returns bad-request response")
  void outboundSystemNotFoundException_returnsResponse() {
    OutboundSystemNotFoundException ex = new OutboundSystemNotFoundException("SYS-001");

    BaseResponse<Object> result = handler.outboundSystemNotFoundException(ex);

    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("hostConfigNotFoundException returns bad-request response")
  void hostConfigNotFoundException_returnsResponse() {
    HostConfigNotFoundException ex = new HostConfigNotFoundException(42L);

    BaseResponse<Object> result = handler.hostConfigNotFoundException(ex);

    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("dataIntegrityViolationException returns bad-request response with baseUrl error")
  void dataIntegrityViolationException_returnsResponse() {
    DataIntegrityViolationException ex = new DataIntegrityViolationException("unique constraint violation");

    BaseResponse<Object> result = handler.dataIntegrityViolationException(ex);

    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("webExchangeBindException returns bad-request response with field and global errors")
  void webExchangeBindException_returnsResponse() {
    WebExchangeBindException ex = mock(WebExchangeBindException.class);
    when(ex.getStackTrace()).thenReturn(new StackTraceElement[0]);
    FieldError fieldError = new FieldError("request", "name", "must not be blank");
    when(ex.getFieldErrors()).thenReturn(List.of(fieldError));
    when(ex.getGlobalErrors()).thenReturn(List.of());

    BaseResponse<Object> result = handler.webExchangeBindException(ex);

    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("illegalArgumentException returns bad-request response")
  void illegalArgumentException_returnsResponse() {
    IllegalArgumentException ex = new IllegalArgumentException("invalid argument");

    BaseResponse<Object> result = handler.illegalArgumentException(ex);

    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("throwable catches any uncaught exception and returns internal-error response")
  void throwable_returnsInternalServerErrorResponse() {
    RuntimeException ex = new RuntimeException("unexpected failure");

    BaseResponse<Object> result = handler.throwable(ex);

    assertThat(result).isNotNull();
  }
}
