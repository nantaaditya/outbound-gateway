package com.nantaaditya.example.model.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RetryDeadLetterProcessRequest(
    @NotBlank(message = "NotBlank") String processType,
    @NotBlank(message = "NotBlank") String processName,
    @Min(value = 1, message = "MustPositive") int size
) {

}
