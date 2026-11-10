package com.paybridge.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception, HttpServletRequest request) {
    return response(exception.status(), exception.code(), exception.getMessage(), request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleInvalidInput(HttpServletRequest request) {
    return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Review the request fields", request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorResponse> handleUnreadableJson(HttpServletRequest request) {
    return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Review the request fields", request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
    LOGGER.error("eventName=api.request.failed errorCode=INTERNAL_ERROR exceptionType={}",
        exception.getClass().getSimpleName());
    return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request);
  }

  private ResponseEntity<ApiErrorResponse> response(
      HttpStatus status,
      String code,
      String message,
      HttpServletRequest request) {
    request.setAttribute("errorCode", code);
    Object requestId = request.getAttribute("requestId");
    return ResponseEntity.status(status).body(new ApiErrorResponse(
        new ApiErrorResponse.ErrorBody(code, message, requestId == null ? "unknown" : requestId.toString())));
  }
}