package com.paybridge;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.api.ApiDtos.ProviderWebhookPayload;
import com.paybridge.service.WebhookSignatureService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ControllerCoverageIntegrationTest {
  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private WebhookSignatureService webhookSignatureService;

    @Test
    void exposesTheApplicationVersionWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service", equalTo("paybridge-api")))
                .andExpect(jsonPath("$.version", equalTo("1.0.0")));
    }

  @Test
  void returnsMerchantScopedPaymentsWebhookReconciliationAndAuditCollections() throws Exception {
    MockHttpSession merchant = login("merchant-northstar");

    mockMvc.perform(get("/api/v1/payment-intents")
            .session(merchant)
            .param("status", "captured")
            .param("q", "subscription")
            .param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentIntents", hasSize(1)))
        .andExpect(jsonPath("$.paymentIntents[0].id", equalTo("intent-demo-captured")));

    mockMvc.perform(get("/api/v1/webhook-events")
            .session(merchant)
            .param("intentId", "intent-demo-captured")
            .param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.webhookEvents", hasSize(1)))
        .andExpect(jsonPath("$.webhookEvents[0].eventId", equalTo("event-demo-captured")));

    mockMvc.perform(get("/api/v1/reconciliations").session(merchant).param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reconciliations[?(@.paymentIntentId=='intent-demo-captured')]", hasSize(1)));
    mockMvc.perform(get("/api/v1/audit-events").session(merchant).param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.auditEvents[0].merchantId", equalTo("merchant-demo-01")));

    MockHttpSession operator = login("operator");
    mockMvc.perform(get("/api/v1/webhook-events").session(operator).param("limit", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.webhookEvents[*].paymentIntentId", hasItem("intent-demo-captured")));
    mockMvc.perform(get("/api/v1/reconciliations").session(operator).param("limit", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reconciliations[*].paymentIntentId", hasItem("intent-demo-captured")));
    mockMvc.perform(get("/api/v1/audit-events").session(operator).param("limit", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.auditEvents[*].eventName", hasItem("payment.capture.completed")));
  }

  @Test
  void rejectsInvalidFiltersAndCrossMerchantWebhookLookups() throws Exception {
    MockHttpSession merchant = login("merchant-northstar");

    mockMvc.perform(get("/api/v1/payment-intents").session(merchant).param("status", "unknown"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code", equalTo("INVALID_STATUS")));
    mockMvc.perform(get("/api/v1/payment-intents").session(merchant).param("limit", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code", equalTo("INVALID_LIMIT")));
    mockMvc.perform(get("/api/v1/reconciliations").session(merchant).param("limit", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code", equalTo("INVALID_LIMIT")));
    mockMvc.perform(get("/api/v1/audit-events").session(merchant).param("limit", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code", equalTo("INVALID_LIMIT")));
    mockMvc.perform(get("/api/v1/webhook-events")
            .session(merchant)
            .param("intentId", "payment-owned-by-another-merchant"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code", equalTo("PAYMENT_NOT_FOUND")));
  }

  @Test
  void exposesTheVoidEndpointOnlyToOperatorsAndReplaysItIdempotently() throws Exception {
    MockHttpSession merchant = login("merchant-northstar");
    String intentId = createIntent(merchant);
    postAction(merchant, "/api/v1/payment-intents/" + intentId + "/authorize", key("authorize"));

    MockHttpSession operator = login("operator");
    String voidPath = "/api/v1/payment-intents/" + intentId + "/void";
    String voidKey = key("void");
    mockMvc.perform(post(voidPath)
            .session(operator)
            .with(csrf())
            .header("Idempotency-Key", voidKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentIntent.status", equalTo("VOIDED")));
    mockMvc.perform(post(voidPath)
            .session(operator)
            .with(csrf())
            .header("Idempotency-Key", voidKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentIntent.status", equalTo("VOIDED")));
    mockMvc.perform(post(voidPath)
            .session(operator)
            .with(csrf())
            .header("Idempotency-Key", key("void-new")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code", equalTo("INVALID_PAYMENT_TRANSITION")));
  }

  @Test
  void rejectsAnUnseededAccountIdWithoutCreatingAnAuthenticatedSession() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/profile-login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(
                Map.of("accountId", "unseeded-account-" + UUID.randomUUID())))
            .with(request -> {
              request.setRemoteAddr("unknown-profile-" + UUID.randomUUID());
              return request;
            }))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code", equalTo("INVALID_REQUEST")))
        .andReturn();

    assertNull(result.getRequest().getSession(false));
    mockMvc.perform(get("/api/v1/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code", equalTo("AUTH_REQUIRED")));
  }

  @Test
  void enforcesSessionCsrfAndCredentialedCorsBoundaries() throws Exception {
    mockMvc.perform(get("/api/v1/overview"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code", equalTo("AUTH_REQUIRED")));

    MockHttpSession merchant = login("merchant-northstar");
    mockMvc.perform(post("/api/v1/auth/logout").session(merchant))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code", equalTo("ACCESS_DENIED")));

    mockMvc.perform(options("/api/v1/overview")
            .header("Origin", "http://127.0.0.1:5601")
            .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5601"))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
  }

  @Test
  void acceptsASignedSyntheticWebhookAtThePublicReceiverEndpoint() throws Exception {
    ProviderWebhookPayload payload = new ProviderWebhookPayload(
        UUID.randomUUID().toString(),
        "PAYMENT_CAPTURED",
        "intent-demo-captured",
        4820,
        Instant.now());
    byte[] body = objectMapper.writeValueAsBytes(payload);

    mockMvc.perform(post("/api/v1/webhooks/receiver")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-PayBridge-Signature", webhookSignatureService.sign(body))
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventId", equalTo(payload.eventId())))
        .andExpect(jsonPath("$.duplicate", equalTo(false)))
        .andExpect(jsonPath("$.signatureVerified", equalTo(true)))
        .andExpect(jsonPath("$.status", equalTo("DELIVERED")));
  }

  private MockHttpSession login(String accountId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/profile-login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(Map.of("accountId", accountId)))
            .with(request -> {
              request.setRemoteAddr("test-client-" + UUID.randomUUID());
              return request;
            }))
        .andExpect(status().isOk())
        .andReturn();
    MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
    assertNotNull(session);
    return session;
  }

  private String createIntent(MockHttpSession session) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/payment-intents")
            .session(session)
            .with(csrf())
            .header("Idempotency-Key", key("create"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(Map.of(
                "amountMinor", 5000,
                "currency", "USD",
                "description", "Synthetic controller test",
                "simulationOutcome", "APPROVE"))))
        .andExpect(status().isCreated())
        .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
    return body.path("paymentIntent").path("id").asText();
  }

  private org.springframework.test.web.servlet.ResultActions postAction(
      MockHttpSession session,
      String path,
      String idempotencyKey) throws Exception {
    return mockMvc.perform(post(path)
        .session(session)
        .with(csrf())
        .header("Idempotency-Key", idempotencyKey));
  }

  private String key(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
