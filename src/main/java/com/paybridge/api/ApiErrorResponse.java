package com.paybridge.api;

public record ApiErrorResponse(ErrorBody error) {
  public record ErrorBody(String code, String message, String requestId) {}
}