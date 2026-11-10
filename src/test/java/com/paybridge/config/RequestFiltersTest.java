package com.paybridge.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class RequestFiltersTest {
  @Test
  void preservesValidCorrelationIdsAndReplacesInvalidValues() throws Exception {
    RequestIdFilter filter = new RequestIdFilter();
    MockHttpServletRequest validRequest = request("/api/v1/overview");
    validRequest.addHeader("X-Request-ID", "request-safe-001");
    validRequest.addHeader("X-Trace-ID", "trace-safe-001");
    MockHttpServletResponse validResponse = new MockHttpServletResponse();

    filter.doFilterInternal(validRequest, validResponse, (request, response) -> {
      assertEquals("request-safe-001", request.getAttribute("requestId"));
      assertEquals("trace-safe-001", request.getAttribute("traceId"));
      assertEquals("request-safe-001", MDC.get("requestId"));
      assertEquals("trace-safe-001", MDC.get("traceId"));
    });

    assertEquals("request-safe-001", validResponse.getHeader("X-Request-ID"));
    assertEquals("trace-safe-001", validResponse.getHeader("X-Trace-ID"));
    assertNull(MDC.get("requestId"));
    assertNull(MDC.get("traceId"));

    MockHttpServletRequest invalidRequest = request("/api/v1/overview");
    invalidRequest.addHeader("X-Request-ID", "contains space");
    invalidRequest.addHeader("X-Trace-ID", "x".repeat(81));
    MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
    filter.doFilterInternal(invalidRequest, invalidResponse, (request, response) -> {});
    assertNotEquals("contains space", invalidResponse.getHeader("X-Request-ID"));
    assertNotEquals("x".repeat(81), invalidResponse.getHeader("X-Trace-ID"));
    assertEquals(36, invalidResponse.getHeader("X-Request-ID").length());
    assertEquals(36, invalidResponse.getHeader("X-Trace-ID").length());
  }

  @Test
  void alwaysClearsCorrelationIdsWhenTheRequestChainFails() {
    RequestIdFilter filter = new RequestIdFilter();
    MockHttpServletRequest request = request("/api/v1/auth/me");
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThrows(ServletException.class,
        () -> filter.doFilterInternal(request, response, (incoming, outgoing) -> {
          throw new ServletException("synthetic test failure");
        }));

    assertNull(MDC.get("requestId"));
    assertNull(MDC.get("traceId"));
  }

  @Test
  void logsApiSuccessClientErrorServerErrorAndUsesSafeRouteTemplates() throws Exception {
    RequestCompletionFilter filter = new RequestCompletionFilter(buildProperties());
    MockHttpServletRequest success = request("/api/v1/overview");
    success.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/overview");
    runCompletion(filter, success, HttpServletResponse.SC_OK);

    MockHttpServletRequest clientError = request("/api/v1/payment-intents/intent-private/capture");
    clientError.setAttribute("requestId", "request-safe-002");
    clientError.setAttribute("traceId", "trace-safe-002");
    clientError.setAttribute("errorCode", "PAYMENT_NOT_FOUND");
    runCompletion(filter, clientError, HttpServletResponse.SC_NOT_FOUND);

    MockHttpServletRequest serverError = request("/api/v1/payment-intents/intent-private/reconcile");
    runCompletion(filter, serverError, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

    MockHttpServletRequest nonApi = request("/actuator/health");
    runCompletion(filter, nonApi, HttpServletResponse.SC_OK);
  }

  @Test
  void logsCompletionEvenWhenTheHandlerChainThrows() {
    RequestCompletionFilter filter = new RequestCompletionFilter(buildProperties());
    MockHttpServletRequest request = request("/api/v1/payment-intents/intent-private");
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThrows(ServletException.class,
        () -> filter.doFilterInternal(request, response, (incoming, outgoing) -> {
          throw new ServletException("synthetic handler failure");
        }));
  }

  private void runCompletion(
      RequestCompletionFilter filter,
      MockHttpServletRequest request,
      int status) throws ServletException, IOException {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilterInternal(request, response, (incoming, outgoing) ->
        ((HttpServletResponse) outgoing).setStatus(status));
    assertEquals(status, response.getStatus());
  }

  private MockHttpServletRequest request(String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI(uri);
    request.setMethod("GET");
    return request;
  }

  private static BuildProperties buildProperties() {
    Properties properties = new Properties();
    properties.setProperty("version", "1.0.0");
    return new BuildProperties(properties);
  }
}
