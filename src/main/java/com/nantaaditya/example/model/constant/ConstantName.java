package com.nantaaditya.example.model.constant;

public interface ConstantName {

  String CALLER_SERVICE_HEADER = "x-caller-service";
  String IDEMPOTENT_HEADER = "x-idempotency-key";

  String REQUEST_ID_MDC = "reqId";

  String ASYNC_POSTFIX_BEAN_NAME = "AsyncTaskExecutor";

  String DEFAULT_SYSTEM_AUDITOR = "SYSTEM";

  int DEFAULT_BUFFER_SIZE = 50;

  String CONTEXTS_CACHE = "contexts";
  String ADDITIONAL_DATA_CACHE = "additional-data";
  String JSLT_CACHE = "jslt";
  String PARAMETERS_CACHE = "parameters";

  String FEATURE_TAG_OBSERVABILITY = "feature";
  String HOST_TAG_OBSERVABILITY = "host";
  String REQUEST_ID_TAG_OBSERVABILITY = "requestId";
  String RESPONSE_CODE_TAG_OBSERVABILITY = "responseCode";
  String CALLER_TAG_OBSERVABILITY = "callerService";
  String ERROR_TAG_OBSERVABILITY = "error";
}
