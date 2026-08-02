package com.nantaaditya.example.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.stream.Stream;
import org.springframework.http.HttpMethod;
import org.springframework.util.StringUtils;

public class HttpMethodMustValidValidator implements ConstraintValidator<HttpMethodMustValid, String> {

  @Override
  public boolean isValid(String s, ConstraintValidatorContext constraintValidatorContext) {
    if (!StringUtils.hasLength(s)) return false;

    return Stream.of(HttpMethod.values())
        .anyMatch(httpMethod -> httpMethod.name().equals(s));
  }
}
