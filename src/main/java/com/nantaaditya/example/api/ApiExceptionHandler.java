package com.nantaaditya.example.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nantaaditya.example.cache.ContextHelper;
import com.nantaaditya.example.helper.ResponseHelper;
import com.nantaaditya.example.model.constant.HeaderConstant;
import com.nantaaditya.example.model.constant.ResponseCode;
import com.nantaaditya.example.model.exception.HostConfigNotFoundException;
import com.nantaaditya.example.model.exception.InvalidParameterException;
import com.nantaaditya.example.model.exception.OutboundSystemNotFoundException;
import com.nantaaditya.example.model.exception.ParameterNotFoundException;
import com.nantaaditya.example.model.logger.AppLogMessage;
import com.nantaaditya.example.model.response.BaseResponse;
import com.nantaaditya.example.observability.ObservationHelper;
import com.nantaaditya.example.observability.TracerHelper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.BadSqlGrammarException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.MissingRequestValueException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Sinks.EmissionException;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Log4j2
@RestControllerAdvice
@RequiredArgsConstructor
public class ApiExceptionHandler {

  private final ObjectMapper objectMapper;
  private final ResponseHelper responseHelper;
  private final ContextHelper contextHelper;
  private final TracerHelper tracerHelper;
  private final ObservationHelper observationHelper;

  private static final String ERROR_LOG = "#ApiError - got exception {}";
  private static final String EXCEPTION_KEY = "exception";
  private static final String EXCEPTION_DETAIL = "exception_detail";
  private static final int START_INDEX = 0;
  private static final String NOT_VALID_ERROR = "NotValid";

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(NoResourceFoundException.class)
  public BaseResponse<Object> noResourceFoundException(NoResourceFoundException ex) {
    return toBaseErrorResponse(ex, error -> {
      Map<String, List<String>> errors = Map.of("endpoint", List.of("not available"));
      BaseResponse<Object> response = responseHelper.failed(ResponseCode.BAD_REQUEST, errors);
      return Tuples.of(errors, response);
    });
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(EmissionException.class)
  public BaseResponse<Object> emissionException(EmissionException ex) {
    return toBaseErrorResponse(ex, error -> {
      Map<String, List<String>> errors = Map.of("publisher", List.of(ex.getReason().name()));
      BaseResponse<Object> response = responseHelper.failed(ResponseCode.BAD_REQUEST, errors);
      return Tuples.of(errors, response);
    });
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  @ExceptionHandler(BadSqlGrammarException.class)
  public BaseResponse<Object> sqlException(BadSqlGrammarException ex) {
    return toBaseErrorResponse(ex, error -> {
      Map<String, List<String>> errors = Map.of(EXCEPTION_KEY, List.of("database error"));
      BaseResponse<Object> response = responseHelper.failed(ResponseCode.INTERNAL_ERROR, Collections.emptyMap());
      return Tuples.of(errors, response);
    });
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(HandlerMethodValidationException.class)
  public BaseResponse<Object> handlerMethodValidationException(HandlerMethodValidationException ex) {
    return toBaseErrorResponse(ex, error -> {
      List<String> errorKeys = ex.getParameterValidationResults()
          .stream()
          .map(ParameterValidationResult::getMethodParameter)
          .map(MethodParameter::getParameter)
          .map(Parameter::getName)
          .toList();
      Map<String, List<String>> errors = new HashMap<>();
      for (String errorKey : errorKeys) {
        errors.put(errorKey, List.of(NOT_VALID_ERROR));
      }
      BaseResponse<Object> response = responseHelper.failed(ResponseCode.INVALID_PARAMS, errors);
      return Tuples.of(errors, response);
    });
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(ConstraintViolationException.class)
  public BaseResponse<Object> constraintViolationException(ConstraintViolationException ex) {
    return toBaseErrorResponse(ex, (ConstraintViolationException error) -> {
      Map<String, List<String>> map = new HashMap<>();

      error.getConstraintViolations()
        .forEach(violation -> {
          for (String attribute : getAttributes(violation)) {
            putEntry(map, attribute, violation.getMessage());
          }
        });

      BaseResponse<Object> response = responseHelper.failed(ResponseCode.INVALID_PARAMS, map);
      return Tuples.of(map, response);
    });
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(OutboundSystemNotFoundException.class)
  public BaseResponse<Object> outboundSystemNotFoundException(OutboundSystemNotFoundException ex) {
    return toBaseErrorResponse(ex, error -> {
      Map<String, List<String>> errors = Map.of("code", List.of("NotFound"));
      BaseResponse<Object> response = responseHelper.failed(ResponseCode.BAD_REQUEST, errors);
      return Tuples.of(errors, response);
    });
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(HostConfigNotFoundException.class)
  public BaseResponse<Object> hostConfigNotFoundException(HostConfigNotFoundException ex) {
    return toBaseErrorResponse(ex, error -> {
      Map<String, List<String>> errors = Map.of("id", List.of("NotFound"));
      BaseResponse<Object> response = responseHelper.failed(ResponseCode.BAD_REQUEST, errors);
      return Tuples.of(errors, response);
    });
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(ParameterNotFoundException.class)
  public BaseResponse<Object> parameterNotFoundException(ParameterNotFoundException ex) {
    return toBaseErrorResponse(ex, error -> {
      Map<String, List<String>> errors = Map.of("injectKeys", List.of("NotFound"));
      BaseResponse<Object> response = responseHelper.failed(ResponseCode.BAD_REQUEST, errors);
      return Tuples.of(errors, response);
    });
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(InvalidParameterException.class)
  public BaseResponse<Object> invalidParameterException(InvalidParameterException ex) {
    return toBaseErrorResponse(ex, error -> {
      Map<String, List<String>> errors = Map.of(ex.getField(), List.of(NOT_VALID_ERROR));
      BaseResponse<Object> response = responseHelper.failed(ResponseCode.INVALID_PARAMS, errors);
      return Tuples.of(errors, response);
    });
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(DataIntegrityViolationException.class)
  public BaseResponse<Object> dataIntegrityViolationException(DataIntegrityViolationException ex) {
    return toBaseErrorResponse(ex, error -> {
      Map<String, List<String>> errors = Map.of("baseUrl", List.of("AlreadyExists"));
      BaseResponse<Object> response = responseHelper.failed(ResponseCode.BAD_REQUEST, errors);
      return Tuples.of(errors, response);
    });
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(WebExchangeBindException.class)
  public BaseResponse<Object> webExchangeBindException(WebExchangeBindException ex) {
    return toBaseErrorResponse(ex, error -> {
      Map<String, List<String>> map = new HashMap<>();
      error.getFieldErrors().forEach(fe ->
          putEntry(map, fe.getField(),
              fe.getDefaultMessage() != null ? fe.getDefaultMessage() : NOT_VALID_ERROR));
      error.getGlobalErrors().forEach(ge ->
          putEntry(map, ge.getObjectName(),
              ge.getDefaultMessage() != null ? ge.getDefaultMessage() : NOT_VALID_ERROR));
      BaseResponse<Object> response = responseHelper.failed(ResponseCode.INVALID_PARAMS, map);
      return Tuples.of(map, response);
    });
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(IllegalArgumentException.class)
  public BaseResponse<Object> illegalArgumentException(IllegalArgumentException ex) {
    return toBaseErrorResponse(ex, error -> {
      Map<String, List<String>> errors = Map.of("message", List.of("Bad request"));
      BaseResponse<Object> response = responseHelper.failed(ResponseCode.BAD_REQUEST, errors);
      return Tuples.of(errors, response);
    });
  }

  /**
   * Input the client got wrong: a missing required parameter/header, a value of the wrong type, or a body that
   * cannot be decoded. Without this it fell into {@link Throwable} below and was reported as a 500, which
   * monitoring counts as a server fault and clients that retry on 5xx repeat for ever.
   */
  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(ServerWebInputException.class)
  public BaseResponse<Object> serverWebInputException(ServerWebInputException ex) {
    return toBaseErrorResponse(ex, error -> {
      String reason = error instanceof MissingRequestValueException ? "NotNull" : NOT_VALID_ERROR;
      Map<String, List<String>> errors = Map.of(inputName(error), List.of(reason));
      BaseResponse<Object> response = responseHelper.failed(ResponseCode.INVALID_PARAMS, errors);
      return Tuples.of(errors, response);
    });
  }

  private String inputName(ServerWebInputException ex) {
    if (ex instanceof MissingRequestValueException missing) {
      return missing.getName();
    }
    MethodParameter parameter = ex.getMethodParameter();
    if (parameter != null && parameter.getParameterName() != null) {
      return parameter.getParameterName();
    }
    return "request";
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  @ExceptionHandler(Throwable.class)
  public BaseResponse<Object> throwable(Throwable ex) {
    return toBaseErrorResponse(ex, error -> {
      Map<String, List<String>> errors = Map.of(EXCEPTION_KEY, List.of("Internal server error"));
      BaseResponse<Object> response = responseHelper.failed(ResponseCode.INTERNAL_ERROR, errors);
      return Tuples.of(errors, response);
    });
  }

  private <T extends Throwable> BaseResponse<Object> toBaseErrorResponse(T ex, Function<T,
      Tuple2<Map<String, List<String>>, BaseResponse<Object>>> function) {
    log.error(AppLogMessage.message(ERROR_LOG, ex.getMessage()).error(ex));

    Tuple2<Map<String, List<String>>, BaseResponse<Object>> tuples = function.apply(ex);

    if (!tuples.getT1().isEmpty()) {
      String exceptionDetail = getErrors(tuples.getT1());
      contextHelper.put(getRequestId(), exceptionDetail);
      observationHelper.publishEvent(EXCEPTION_DETAIL, exceptionDetail);
    }

    return tuples.getT2();
  }

  private String getRequestId() {
    return tracerHelper.getBaggage(HeaderConstant.REQUEST_ID);
  }

  private String getErrors(Map<String, List<String>> violations) {
    try {
      return objectMapper.writeValueAsString(violations);
    } catch (JsonProcessingException e) {
      log.error(AppLogMessage.message("#ApiError - failed convert errors").error(e));
      return null;
    }
  }

  private void putEntry(Map<String, List<String>> map, String key, String value) {
    map.computeIfAbsent(key, r -> new LinkedList<>());
    map.get(key).add(value);
  }

  private String[] getAttributes(ConstraintViolation<?> constraintViolation) {
    String[] values = (String[]) constraintViolation.getConstraintDescriptor()
        .getAttributes()
        .get("path");
    if (values == null || values.length == START_INDEX) {
      return getAttributesFromPath(constraintViolation);
    } else {
      return values;
    }
  }

  private String[] getAttributesFromPath(ConstraintViolation<?> constraintViolation) {
    Path path = constraintViolation.getPropertyPath();

    StringBuilder builder = new StringBuilder();
    path.forEach(node -> {
      if (node.getName() != null) {
        if (builder.length() > START_INDEX) {
          builder.append(".");
        }
        builder.append(node.getName());
      }
    });

    return new String[]{builder.toString()};
  }
}
