package com.nantaaditya.example.base;

import java.util.Map;
import org.springframework.http.HttpStatus;

public record RestApiAssertion<T> (
   HttpStatus httpStatus,
   Map<String, String> headers,
   T response
) {}