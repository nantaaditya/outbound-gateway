package com.nantaaditya.example.model.logger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonLogIsoMessage(
    String direction,
    String mti,
    @JsonProperty("data_element")
    Map<String, String> dataElements
) {

}
