package com.paybridge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.domain.AppRole;
import com.paybridge.domain.DemoPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiErrorHandlingTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void writesCorrelatedSafeErrorsAndUsesUnknownWhenRequestIdIsMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute("requestId", "request-test-001");
    MockHttpServletResponse response = new MockHttpServletResponse();

    ApiErrorWriter.write(request, response, objectMapper, 403, "ACCESS_DENIED", "Not permitted");

    assertEquals(403, response.getStatus());
    assertEquals("application/json", response.getContentType());
    assertEquals("ACCESS_DENIED", request.getAttribute("errorCode"));
    JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
    assertEquals("request-test-001", body.path("error").path("requestId").asText());
    assertEquals("Not permitted", body.path("error").path("message").asText());

    MockHttpServletResponse uncorrelated = new MockHttpServletResponse();
    ApiErrorWriter.write(new MockHttpServletRequest(), uncorrelated, objectMapper, 500, "ERROR", "Safe message");
    JsonNode fallback = objectMapper.readTree(uncorrelated.getContentAsByteArray());
    assertEquals("unknown", fallback.path("error").path("requestId").asText());
  }

  @Test
  void mapsApiValidationAndUnexpectedFailuresToStableResponses() {
    MockHttpServletRequest request = requestWithId();
    ResponseEntity<ApiErrorResponse> apiError = handler.handleApiException(
        new ApiException(HttpStatus.CONFLICT, "PAYMENT_STATE_CONFLICT", "Reload and retry"), request);
    assertEquals(HttpStatus.CONFLICT, apiError.getStatusCode());
    assertEquals("PAYMENT_STATE_CONFLICT", apiError.getBody().error().code());
    assertEquals("request-test-002", apiError.getBody().error().requestId());
    ResponseEntity<ApiErrorResponse> uncorrelated = handler.handleApiException(
        new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Review the request fields"),
        new MockHttpServletRequest());
    assertEquals("unknown", uncorrelated.getBody().error().requestId());

    ResponseEntity<ApiErrorResponse> invalid = handler.handleInvalidInput(request);
    assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
    assertEquals("INVALID_REQUEST", invalid.getBody().error().code());
    assertEquals("Review the request fields", invalid.getBody().error().message());

    ResponseEntity<ApiErrorResponse> unreadable = handler.handleUnreadableJson(request);
    assertEquals(HttpStatus.BAD_REQUEST, unreadable.getStatusCode());
    assertEquals("INVALID_REQUEST", unreadable.getBody().error().code());

    ResponseEntity<ApiErrorResponse> unexpected = handler.handleUnexpected(
        new IllegalStateException("must not be returned"), request);
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, unexpected.getStatusCode());
    assertEquals("INTERNAL_ERROR", unexpected.getBody().error().code());
    assertEquals("An unexpected error occurred", unexpected.getBody().error().message());
    assertFalse(unexpected.getBody().error().message().contains("must not"));
  }

  @Test
  void exposesOnlyServerAssignedRoleAuthorityAndMerchantIdentity() {
    DemoPrincipal merchant = new DemoPrincipal(
        "user-merchant", "Synthetic Merchant", AppRole.MERCHANT, "merchant-demo-01");
    DemoPrincipal auditor = new DemoPrincipal("user-auditor", "Synthetic Auditor", AppRole.AUDITOR, null);

    assertTrue(merchant.isMerchant());
    assertFalse(auditor.isMerchant());
    assertEquals("ROLE_MERCHANT", merchant.role().authority());
    assertEquals("ROLE_AUDITOR", auditor.role().authority());
  }

  private MockHttpServletRequest requestWithId() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute("requestId", "request-test-002");
    return request;
  }
}
