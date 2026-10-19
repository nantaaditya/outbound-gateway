package com.nantaaditya.example.model.request.admin;

import com.nantaaditya.example.model.constant.ParameterInjectLocation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpsertParametersRequest(
    @NotEmpty(message = "NotEmpty")
    @Size(max = 50, message = "NotValid")
    List<@Valid @NotNull(message = "NotNull") Item> parameters
) {

  public record Item(
      @Schema(description = "key the client sends in injectKeys")
      @NotBlank(message = "NotBlank")
      @Size(max = 100, message = "NotValid")
      String key,

      @Schema(description = "value to inject, stored encrypted and never returned")
      @NotBlank(message = "NotBlank")
      @Size(max = 4096, message = "NotValid")
      String value,

      @NotNull(message = "NotNull")
      ParameterInjectLocation location,

      @Schema(description = "HEADER: header name, QUERY_PARAM: query param name, BODY: dot path, ex: meta.partnerId")
      @NotBlank(message = "NotBlank")
      @Size(max = 255, message = "NotValid")
      String targetName,

      @Schema(description = "defaults to true when omitted")
      Boolean active
  ) {

    public boolean isActive() {
      return active == null || active;
    }
  }
}
