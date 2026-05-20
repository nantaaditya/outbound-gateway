package com.nantaaditya.example.model.response;

public record TestConnectionResponse(boolean success, int httpStatus, String message) {}
