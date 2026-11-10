package com.paybridge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class ApiErrorWriter {
  private ApiErrorWriter() {}

  public static void write(
      HttpServletRequest request,
      HttpServletResponse response,
      ObjectMapper objectMapper,
      int status,
      String code,
      String message) throws IOException {
    request.setAttribute("errorCode", code);
    response.setStatus(status);
    response.setContentType("application/json");
    Object requestId = request.getAttribute("requestId");
    objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
        new ApiErrorResponse.ErrorBody(code, message, requestId == null ? "unknown" : requestId.toString())));
  }
}