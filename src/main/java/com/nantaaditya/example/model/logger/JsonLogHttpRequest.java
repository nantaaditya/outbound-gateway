package com.nantaaditya.example.model.logger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.util.MultiValueMap;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonLogHttpRequest(
    @JsonProperty("http_method")
    String httpMethod,
    String uri,
    MultiValueMap<String, String> headers,
    Object body
) {

}