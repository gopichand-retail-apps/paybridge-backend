package com.paybridge;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.persistence.PaymentRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentWorkflowIntegrationTest {
  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

  @Test
  void exposesCsrfBootstrapAndWorkspaceProfilesBeforeLogin() throws Exception {
    MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode csrfBody = objectMapper.readTree(csrfResult.getResponse().getContentAsByteArray());
    assertTrue(csrfBody.path("token").isTextual() && !csrfBody.path("token").asText().isBlank());

    mockMvc.perform(get("/api/v1/auth/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accounts", hasSize(4)))
        .andExpect(jsonPath("$.accounts[*].role", containsInAnyOrder(
            "AUDITOR", "MERCHANT", "MERCHANT", "OPERATOR")));
  }

  @Test
  void createsARotatedServerManagedSessionAndInvalidatesItOnLogout() throws Exception {
    MockHttpSession existingSession = new MockHttpSession();
    existingSession.setAttribute("preLoginState", "preserved");
    String previousSessionId = existingSession.getId();

    MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/profile-login")
            .session(existingSession)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(Map.of("accountId", "merchant-cedar"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.localProfileSession", equalTo(true)))
        .andExpect(jsonPath("$.user.id", equalTo("user-merchant-02")))
        .andExpect(jsonPath("$.user.role", equalTo("MERCHANT")))
        .andExpect(jsonPath("$.user.merchantId", equalTo("merchant-demo-02")))
        .andReturn();

    MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
    assertNotNull(session);
    assertNotEquals(previousSessionId, session.getId());
    assertEquals("preserved", session.getAttribute("preLoginState"));

    mockMvc.perform(get("/api/v1/auth/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.id", equalTo("user-merchant-02")))
        .andExpect(jsonPath("$.user.role", equalTo("MERCHANT")));

    mockMvc.perform(post("/api/v1/auth/logout").session(session).with(csrf()))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/auth/me").session(session))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void runsCreateAuthorizeCaptureSignedWebhookAndReconcileIdempotently() throws Exception {
    MockHttpSession merchant = login("merchant-northstar");
        mockMvc.perform(get("/api/v1/overview").session(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIntentCount").isNumber());

    String createKey = key("create");
    String intentId = createIntent(merchant, createKey, "APPROVE", 12500);

    mockMvc.perform(get("/api/v1/payment-intents/{id}", intentId).session(merchant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentIntent.merchantName", equalTo("Northstar Goods")));

    mockMvc.perform(post("/api/v1/payment-intents")
            .session(merchant)
            .with(csrf())
            .header("Idempotency-Key", createKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createRequest("APPROVE", 12500)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate", equalTo(true)))
        .andExpect(jsonPath("$.paymentIntent.id", equalTo(intentId)));

    mockMvc.perform(post("/api/v1/payment-intents")
            .session(merchant)
            .with(csrf())
            .header("Idempotency-Key", createKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createRequest("APPROVE", 13500)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code", equalTo("IDEMPOTENCY_CONFLICT")));

    postAction(merchant, "/api/v1/payment-intents/" + intentId + "/authorize", key("authorize"))
        .andExpect(jsonPath("$.paymentIntent.status", equalTo("AUTHORIZED")));
    postAction(merchant, "/api/v1/payment-intents/" + intentId + "/capture", key("capture"))
        .andExpect(jsonPath("$.paymentIntent.status", equalTo("CAPTURED")));

    mockMvc.perform(post("/api/v1/payment-intents/{id}/webhook-deliveries", intentId)
            .session(merchant)
            .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deliveries", hasSize(1)))
        .andExpect(jsonPath("$.deliveries[0].signatureVerified", equalTo(true)))
        .andExpect(jsonPath("$.deliveries[0].duplicate", equalTo(false)));

    mockMvc.perform(post("/api/v1/payment-intents/{id}/webhook-deliveries", intentId)
            .session(merchant)
            .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deliveries", hasSize(0)));

    MockHttpSession operator = login("operator");
    mockMvc.perform(post("/api/v1/payment-intents/{id}/reconcile", intentId)
            .session(operator)
            .with(csrf())
            .header("Idempotency-Key", key("reconcile")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reconciliation.result", equalTo("MATCHED")))
        .andExpect(jsonPath("$.reconciliation.observedCapturedMinor", equalTo(12500)));

    mockMvc.perform(get("/api/v1/webhook-events").session(operator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.webhookEvents[?(@.paymentIntentId=='" + intentId + "')]", hasSize(1)));
  }

  @Test
  void replaysCaptureWithoutCreatingAnotherSettlementOrOutboxEvent() throws Exception {
    MockHttpSession merchant = login("merchant-northstar");
    String intentId = createIntent(merchant, key("capture-replay-create"), "APPROVE", 12500);
    postAction(merchant, "/api/v1/payment-intents/" + intentId + "/authorize", key("capture-replay-auth"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentIntent.status", equalTo("AUTHORIZED")));

    String capturePath = "/api/v1/payment-intents/" + intentId + "/capture";
    String captureKey = key("capture-replay");
    postAction(merchant, capturePath, captureKey)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentIntent.status", equalTo("CAPTURED")))
        .andExpect(jsonPath("$.paymentIntent.capturedAmountMinor", equalTo(12500)));
    postAction(merchant, capturePath, captureKey)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentIntent.status", equalTo("CAPTURED")))
        .andExpect(jsonPath("$.paymentIntent.capturedAmountMinor", equalTo(12500)));

    assertEquals(1, jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_captures WHERE payment_intent_id = ?", Integer.class, intentId).intValue());
    assertEquals(1, jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM webhook_outbox
        WHERE payment_intent_id = ? AND event_type = 'PAYMENT_CAPTURED'
        """, Integer.class, intentId).intValue());
    assertEquals(1, jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM idempotency_records
        WHERE actor_id = ? AND operation = 'CAPTURE' AND idempotency_key = ?
        """, Integer.class, "user-merchant-01", captureKey).intValue());
  }

      @Test
      void propagatesRequestAndTraceIdentifiersOnVersionedApiResponses() throws Exception {
        MockHttpSession merchant = login("merchant-northstar");
        mockMvc.perform(get("/api/v1/overview")
            .session(merchant)
            .header("X-Request-ID", "request-correlated-001")
            .header("X-Trace-ID", "trace-correlated-001"))
          .andExpect(status().isOk())
          .andExpect(header().string("X-Request-ID", "request-correlated-001"))
          .andExpect(header().string("X-Trace-ID", "trace-correlated-001"));
      }

  @Test
  void preventsCrossMerchantReadsAndKeepsAuditorsReadOnly() throws Exception {
    MockHttpSession cedarMerchant = login("merchant-cedar");
    String foreignIntentId = createIntent(cedarMerchant, key("tenant-create"), "APPROVE", 5000);
    MockHttpSession northstarMerchant = login("merchant-northstar");

    mockMvc.perform(get("/api/v1/payment-intents/{id}", foreignIntentId).session(northstarMerchant))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code", equalTo("PAYMENT_NOT_FOUND")));
    mockMvc.perform(get("/api/v1/payment-intents").session(northstarMerchant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentIntents[*].id", not(hasItem(foreignIntentId))));
    postAction(northstarMerchant, "/api/v1/payment-intents/" + foreignIntentId + "/capture",
        key("foreign-capture"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code", equalTo("PAYMENT_NOT_FOUND")));

    MockHttpSession auditor = login("auditor");
    mockMvc.perform(post("/api/v1/payment-intents")
            .session(auditor)
            .with(csrf())
            .header("Idempotency-Key", key("auditor-create"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createRequest("APPROVE", 5000)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code", equalTo("ROLE_REQUIRED")));

    postAction(auditor, "/api/v1/payment-intents/intent-demo-authorized/capture", key("auditor-capture"))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/api/v1/auth/profile-login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"auditor\",\"role\":\"OPERATOR\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void recordsOnlySimulatedRefundsAndRejectsAmountsBeyondTheCapture() throws Exception {
    MockHttpSession merchant = login("merchant-northstar");
    String intentId = createIntent(merchant, key("refund-create"), "APPROVE", 12500);
    postAction(merchant, "/api/v1/payment-intents/" + intentId + "/authorize", key("refund-auth"));
    postAction(merchant, "/api/v1/payment-intents/" + intentId + "/capture", key("refund-capture"));

    MockHttpSession operator = login("operator");
    mockMvc.perform(post("/api/v1/payment-intents/{id}/refunds", intentId)
            .session(operator)
            .with(csrf())
            .header("Idempotency-Key", key("refund"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(Map.of("amountMinor", 2500, "reason", "Customer request"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentIntent.status", equalTo("PARTIALLY_REFUNDED")))
        .andExpect(jsonPath("$.paymentIntent.refundedAmountMinor", equalTo(2500)));

    mockMvc.perform(post("/api/v1/payment-intents/{id}/refunds", intentId)
            .session(operator)
            .with(csrf())
            .header("Idempotency-Key", key("refund-too-much"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(Map.of("amountMinor", 10001, "reason", "Excess request"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code", equalTo("REFUND_EXCEEDS_CAPTURE")));
  }

    @Test
    void concurrentRefundUpdatesCannotExceedTheCapturedAmount() throws Exception {
        MockHttpSession merchant = login("merchant-northstar");
        String intentId = createIntent(merchant, key("concurrent-refund-create"), "APPROVE", 10_000);
        postAction(merchant, "/api/v1/payment-intents/" + intentId + "/authorize", key("concurrent-refund-auth"));
        postAction(merchant, "/api/v1/payment-intents/" + intentId + "/capture", key("concurrent-refund-capture"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var update = (java.util.concurrent.Callable<Boolean>) () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return paymentRepository.updateRefund(intentId, 7_000);
            };
            var first = executor.submit(update);
            var second = executor.submit(update);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            long successfulUpdates = java.util.stream.Stream.of(
                    first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
                    .filter(Boolean::booleanValue)
                    .count();
            assertEquals(1L, successfulUpdates);

            MockHttpSession operator = login("operator");
            mockMvc.perform(get("/api/v1/payment-intents/{id}", intentId).session(operator))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentIntent.refundedAmountMinor", equalTo(7000)));
        } finally {
            executor.shutdownNow();
        }
    }

  @Test
  void handlesDeclinesAndRejectsUnsignedWebhookRequests() throws Exception {
    MockHttpSession merchant = login("merchant-northstar");
    String intentId = createIntent(merchant, key("decline-create"), "DECLINE", 9900);
    postAction(merchant, "/api/v1/payment-intents/" + intentId + "/authorize", key("decline-auth"))
        .andExpect(jsonPath("$.paymentIntent.status", equalTo("DECLINED")));
    postAction(merchant, "/api/v1/payment-intents/" + intentId + "/capture", key("decline-capture"))
        .andExpect(status().isConflict());

    mockMvc.perform(post("/api/v1/webhooks/receiver")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-PayBridge-Signature", "sha256=00")
            .content("{\"eventId\":\"event-1\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code", equalTo("INVALID_WEBHOOK_SIGNATURE")));
  }

  @Test
  void acceptsOnlyMinimalAuthenticatedClientErrorEvents() throws Exception {
    mockMvc.perform(post("/api/v1/client-errors")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"eventName\":\"ui.error\"}"))
        .andExpect(status().isUnauthorized());

    MockHttpSession merchant = login("merchant-northstar");
    mockMvc.perform(post("/api/v1/client-errors")
            .session(merchant)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"eventName\":\"ui.error\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted", equalTo(true)));

    mockMvc.perform(post("/api/v1/client-errors")
            .session(merchant)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"eventName\":\"ui.error\",\"stack\":\"not accepted\"}"))
        .andExpect(status().isBadRequest());
  }

  private MockHttpSession login(String accountId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/profile-login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(Map.of("accountId", accountId))))
        .andExpect(status().isOk())
        .andReturn();
    return (MockHttpSession) result.getRequest().getSession(false);
  }

  private String createIntent(MockHttpSession session, String idempotencyKey, String outcome, long amountMinor)
      throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/payment-intents")
            .session(session)
            .with(csrf())
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createRequest(outcome, amountMinor)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.paymentIntent.status", equalTo("CREATED")))
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

  private byte[] createRequest(String outcome, long amountMinor) throws Exception {
    return objectMapper.writeValueAsBytes(Map.of(
        "amountMinor", amountMinor,
        "currency", "USD",
        "description", "Synthetic test payment",
        "simulationOutcome", outcome));
  }

  private String key(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}