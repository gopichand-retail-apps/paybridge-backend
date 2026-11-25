package com.paybridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.Callable;
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

/**
 * PB-01 and PB-02: overlapping requests run through the real controllers, services and H2 engine,
 * each on its own pooled connection and transaction. Rounds repeat to give genuine overlap.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentOverlapIntegrationTest {
  private static final int ROUNDS = 12;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbc;

  private ApiTestClient api;
  private MockHttpSession merchant;
  private MockHttpSession operatorOne;
  private MockHttpSession operatorTwo;

  @BeforeEach
  void signIn() throws Exception {
    api = new ApiTestClient(mockMvc, objectMapper);
    merchant = api.login("merchant-northstar");
    operatorOne = api.login("operator");
    operatorTwo = api.login("operator");
  }

  @Test
  void twoOverlappingRefundsNeverTotalMoreThanWasCapturedAndTheRejectedOneChangesNothing() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      String intentId = api.createCapturedIntent(merchant, 10_000);

      List<MockHttpServletResponse> responses = api.overlap(List.of(
          refund(operatorOne, intentId, api.key("refund-a"), 7_000, "Opposing operator A"),
          refund(operatorTwo, intentId, api.key("refund-b"), 7_000, "Opposing operator B")));

      List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList();
      assertEquals(List.of(200, 409), statuses, "round " + round);
      assertEquals("REFUND_EXCEEDS_CAPTURE", responses.stream()
          .filter(response -> response.getStatus() == 409)
          .map(response -> body(response).path("error").path("code").asText()).findFirst().orElseThrow());
      assertEquals(7_000L, scalar("SELECT refunded_amount_minor FROM payment_intents WHERE id = ?", intentId));
      assertEquals(1L, scalar("SELECT COUNT(*) FROM payment_refunds WHERE payment_intent_id = ?", intentId));
      assertEquals(7_000L, scalar("SELECT SUM(amount_minor) FROM payment_refunds WHERE payment_intent_id = ?",
          intentId));
      assertEquals(1L, scalar("""
          SELECT COUNT(*) FROM webhook_outbox
          WHERE payment_intent_id = ? AND event_type = 'PAYMENT_REFUNDED'
          """, intentId));
      assertEquals(1L, scalar("""
          SELECT COUNT(*) FROM audit_events
          WHERE entity_id = ? AND event_name = 'payment.refund.recorded'
          """, intentId));
    }
  }

  @Test
  void overlappingRefundsWithTheSameKeyApplyOnceAndBothReceiveTheOriginalResult() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      String intentId = api.createCapturedIntent(merchant, 10_000);
      String sharedKey = api.key("refund-same");

      List<MockHttpServletResponse> responses = api.overlap(List.of(
          refund(operatorOne, intentId, sharedKey, 2_000, "Double click"),
          refund(operatorOne, intentId, sharedKey, 2_000, "Double click")));

      assertEquals(List.of(200, 200),
          responses.stream().map(MockHttpServletResponse::getStatus).toList(), "round " + round);
      responses.forEach(response -> assertEquals(2_000L,
          body(response).path("paymentIntent").path("refundedAmountMinor").asLong()));
      assertEquals(2_000L, scalar("SELECT refunded_amount_minor FROM payment_intents WHERE id = ?", intentId));
      assertEquals(1L, scalar("SELECT COUNT(*) FROM payment_refunds WHERE payment_intent_id = ?", intentId));
      assertEquals(1L, scalar("""
          SELECT COUNT(*) FROM webhook_outbox
          WHERE payment_intent_id = ? AND event_type = 'PAYMENT_REFUNDED'
          """, intentId));
    }
  }

  @Test
  void overlappingCreatesWithTheSameKeyProduceOneIntentAndOneReplay() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      String sharedKey = api.key("create-same");
      Callable<MockHttpServletResponse> create =
          () -> api.send(api.createRequest(merchant, sharedKey, 4_200, "Double submit"));

      List<MockHttpServletResponse> responses = api.overlap(List.of(create, create));

      assertEquals(List.of(200, 201),
          responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList(), "round " + round);
      assertEquals(1, responses.stream().map(response -> body(response).path("paymentIntent").path("id").asText())
          .distinct().count());
      assertEquals(1L, scalar(
          "SELECT COUNT(*) FROM payment_intents WHERE merchant_id = 'merchant-demo-01' AND idempotency_key = ?",
          sharedKey));
    }
  }

  @Test
  void overlappingCapturesWithTheSameKeyCaptureOnce() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      String intentId = api.createIntent(merchant, 6_000);
      api.send(api.actionRequest(merchant, intentId, "authorize", api.key("authorize")));
      String sharedKey = api.key("capture-same");
      Callable<MockHttpServletResponse> capture =
          () -> api.send(api.actionRequest(merchant, intentId, "capture", sharedKey));

      List<MockHttpServletResponse> responses = api.overlap(List.of(capture, capture));

      assertEquals(List.of(200, 200),
          responses.stream().map(MockHttpServletResponse::getStatus).toList(), "round " + round);
      assertEquals(1L, scalar("SELECT COUNT(*) FROM payment_captures WHERE payment_intent_id = ?", intentId));
      assertEquals(6_000L, scalar("SELECT captured_amount_minor FROM payment_intents WHERE id = ?", intentId));
      assertTrue(responses.stream().allMatch(response ->
          "CAPTURED".equals(body(response).path("paymentIntent").path("status").asText())));
    }
  }

  private Callable<MockHttpServletResponse> refund(
      MockHttpSession session, String intentId, String key, long amountMinor, String reason) {
    return () -> api.send(api.refundRequest(session, intentId, key, amountMinor, reason));
  }

  private com.fasterxml.jackson.databind.JsonNode body(MockHttpServletResponse response) {
    try {
      return api.json(response);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private long scalar(String sql, Object... arguments) {
    Long value = jdbc.queryForObject(sql, Long.class, arguments);
    return value == null ? 0L : value;
  }
}
