package com.paybridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** PB-02: a refund is visible with actor, reason, reference, currency, amount and remaining balance. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefundHistoryIntegrationTest {
  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbc;

  private ApiTestClient api;
  private MockHttpSession merchant;
  private MockHttpSession operator;

  @BeforeEach
  void signIn() throws Exception {
    api = new ApiTestClient(mockMvc, objectMapper);
    merchant = api.login("merchant-northstar");
    operator = api.login("operator");
  }

  @Test
  void listsEachRefundWithActorReasonReferenceCurrencyAmountAndRemainingBalanceNewestFirst() throws Exception {
    String intentId = api.createCapturedIntent(merchant, 10_000);
    assertEquals(200, api.send(api.refundRequest(operator, intentId, api.key("r1"), 2_500, "Customer request"))
        .getStatus());
    assertEquals(200, api.send(api.refundRequest(operator, intentId, api.key("r2"), 1_500, "Damaged item"))
        .getStatus());

    JsonNode history = api.json(api.send(api.refundHistoryRequest(operator, intentId)));

    assertEquals(intentId, history.path("paymentIntentId").asText());
    assertEquals("USD", history.path("currency").asText());
    assertEquals(10_000, history.path("capturedAmountMinor").asLong());
    assertEquals(4_000, history.path("refundedAmountMinor").asLong());
    assertEquals(6_000, history.path("remainingRefundableMinor").asLong());
    assertEquals(2, history.path("refunds").size());

    JsonNode newest = history.path("refunds").get(0);
    assertEquals("Damaged item", newest.path("reason").asText());
    assertEquals(1_500, newest.path("amountMinor").asLong());
    assertEquals(6_000, newest.path("remainingAfterMinor").asLong());
    assertEquals("USD", newest.path("currency").asText());
    assertEquals("user-operator-01", newest.path("actorId").asText());
    assertEquals("Morgan Patel", newest.path("actorName").asText());
    assertFalse(newest.path("id").asText().isBlank());
    assertFalse(newest.path("createdAt").asText().isBlank());

    JsonNode oldest = history.path("refunds").get(1);
    assertEquals("Customer request", oldest.path("reason").asText());
    assertEquals(2_500, oldest.path("amountMinor").asLong());
    assertEquals(7_500, oldest.path("remainingAfterMinor").asLong());
    assertTrue(!oldest.path("id").asText().equals(newest.path("id").asText()));
  }

  @Test
  void aRejectedRefundIsNotListedAndLeavesTheRemainingBalanceUntouched() throws Exception {
    String intentId = api.createCapturedIntent(merchant, 5_000);
    assertEquals(200, api.send(api.refundRequest(operator, intentId, api.key("ok"), 3_000, "First part"))
        .getStatus());
    MockHttpServletResponse rejected =
        api.send(api.refundRequest(operator, intentId, api.key("too-much"), 2_001, "Too much"));
    assertEquals(409, rejected.getStatus());

    JsonNode history = api.json(api.send(api.refundHistoryRequest(operator, intentId)));

    assertEquals(1, history.path("refunds").size());
    assertEquals(2_000, history.path("remainingRefundableMinor").asLong());
    assertEquals(3_000, history.path("refundedAmountMinor").asLong());
  }

  @Test
  void anIntentWithNoRefundsReturnsAnEmptyHistoryWithTheFullBalance() throws Exception {
    String intentId = api.createCapturedIntent(merchant, 5_000);

    JsonNode history = api.json(api.send(api.refundHistoryRequest(operator, intentId)));

    assertEquals(0, history.path("refunds").size());
    assertEquals(5_000, history.path("remainingRefundableMinor").asLong());
  }

  @Test
  void theOwningMerchantAndAuditorCanReadButAnotherMerchantAndAnonymousCallersCannot() throws Exception {
    String intentId = api.createCapturedIntent(merchant, 5_000);
    api.send(api.refundRequest(operator, intentId, api.key("seen"), 1_000, "Visible refund"));

    assertEquals(200, api.send(api.refundHistoryRequest(merchant, intentId)).getStatus());
    assertEquals(200, api.send(api.refundHistoryRequest(api.login("auditor"), intentId)).getStatus());

    MockHttpServletResponse foreign = api.send(api.refundHistoryRequest(api.login("merchant-cedar"), intentId));
    assertEquals(404, foreign.getStatus());
    assertEquals("PAYMENT_NOT_FOUND", api.json(foreign).path("error").path("code").asText());
    assertFalse(foreign.getContentAsString().contains("Visible refund"));

    assertEquals(401, api.send(api.anonymousRefundHistoryRequest(intentId)).getStatus());
  }

  @Test
  void aRefundRecordedBeforeActorTrackingReadsAsNotRecordedInsteadOfInventingAnActor() throws Exception {
    String intentId = api.createCapturedIntent(merchant, 5_000);
    jdbc.update("""
        INSERT INTO payment_refunds (id, payment_intent_id, amount_minor, reason, idempotency_key)
        VALUES (?, ?, 500, 'Legacy synthetic refund', ?)
        """, UUID.randomUUID().toString(), intentId, UUID.randomUUID().toString());

    JsonNode row = api.json(api.send(api.refundHistoryRequest(operator, intentId))).path("refunds").get(0);

    assertTrue(row.path("actorId").isNull());
    assertTrue(row.path("actorName").isNull());
    assertTrue(row.path("remainingAfterMinor").isNull());
    assertEquals("Legacy synthetic refund", row.path("reason").asText());
    assertEquals(500, row.path("amountMinor").asLong());
  }
}
