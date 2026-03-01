package com.nantaaditya.example.model.logger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.util.MultiValueMap;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonLogHttpResponse(
    @JsonProperty("http_method")
    String httpMethod,
    String uri,
    @JsonProperty("http_code")
    String httpCode,
    String duration,
    MultiValueMap<String, String> headers,
    Object body
) {

}