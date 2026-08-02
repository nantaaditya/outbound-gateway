package com.nantaaditya.example.model.request.admin;

import com.nantaaditya.example.validation.HttpMethodMustValid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import javax.annotation.RegEx;

public record CreateOutboundSystemRequest(
    @NotBlank(message = "NotBlank")
    @Pattern(
        regexp = "^[a-zA-Z0-9_-]+$",
        message = "NotValid"
    )
    @Size(max = 100, message = "TooLong")
    String code,
    @NotBlank(message = "NotBlank")
    @Size(max = 100, message = "TooLong")
    String name,
    @Size(max = 100, message = "TooLong")
    String description,
    @NotBlank(message = "NotBlank")
    @HttpMethodMustValid(message = "NotValid")
    String httpMethod,
    @NotNull(message = "NotNull")
    @Min(value = 1, message = "NotValid")
    Long hostConfigurationId,
    @NotBlank(message = "NotBlank")
    @Size(max = 500, message = "NotValid")
    String apiPath,
    boolean active,
    @Min(value = 0, message = "NotValid")
    int idempotencyTtlHours
) {

}
