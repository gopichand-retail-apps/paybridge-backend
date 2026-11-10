package com.paybridge.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.paybridge.api.ApiDtos.PaymentIntentView;
import com.paybridge.api.ApiDtos.ProviderWebhookPayload;
import com.paybridge.api.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaymentRepositoryIntegrationTest {
  @Autowired
  private PaymentRepository repository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void scopesSearchesAndMapsPaymentIntentsAndIdempotencyRecords() {
    String northstarId = newIntent("merchant-demo-01", "Repository synthetic order");
    String cedarId = newIntent("merchant-demo-02", "Cedar scoped invoice");

    assertTrue(repository.findPayments(null, null, null, 100).size() >= 2);
    assertTrue(repository.findPayments("merchant-demo-01", "CREATED", "synthetic", 100)
        .stream().anyMatch(payment -> payment.id().equals(northstarId)));
    assertTrue(repository.findPayments(null, null, northstarId.substring(0, 8), 100)
        .stream().anyMatch(payment -> payment.id().equals(northstarId)));
    assertTrue(repository.findPayments(null, null, " ", 100)
        .stream().anyMatch(payment -> payment.id().equals(cedarId)));

    PaymentIntentView unscoped = repository.findPayment(northstarId, null).orElseThrow();
    assertEquals("Northstar Goods", unscoped.merchantName());
    assertTrue(repository.findPayment(northstarId, "merchant-demo-01").isPresent());
    assertFalse(repository.findPayment(northstarId, "merchant-demo-02").isPresent());
    assertTrue(repository.findCreateIdempotency("merchant-demo-01", "missing-create-key").isEmpty());
    assertEquals(northstarId,
        repository.findCreateIdempotency("merchant-demo-01", idempotencyKeyFor(northstarId))
            .orElseThrow().intentId());

    repository.insertActionIdempotency(
        "user-merchant-01", "AUTHORIZE", "repo-action-001", "safe-request-hash", northstarId);
    assertTrue(repository.findActionIdempotency("user-merchant-01", "CAPTURE", "repo-action-001").isEmpty());
    assertEquals(northstarId, repository.findActionIdempotency(
        "user-merchant-01", "AUTHORIZE", "repo-action-001").orElseThrow().entityId());
  }

  @Test
  void recordsAttemptCaptureVoidAndOptimisticStateConflicts() {
    String authorizeId = newIntent("merchant-demo-01", "Authorize repository intent");
    assertEquals(0, repository.countAttempts(authorizeId));
    repository.insertAttempt(
        authorizeId, 1, "APPROVED", "SIM-AUTH-test", null, UUID.randomUUID().toString());
    assertEquals(1, repository.countAttempts(authorizeId));
    repository.updateAuthorization(authorizeId, "AUTHORIZED");
    assertEquals("AUTHORIZED", repository.findPayment(authorizeId, null).orElseThrow().status().name());
    assertNotNull(repository.findPayment(authorizeId, null).orElseThrow().authorizedAt());

    repository.insertCapture(
        authorizeId, 5000, "SIM-CAP-test", UUID.randomUUID().toString());
    repository.updateCapture(authorizeId, 5000);
    PaymentIntentView captured = repository.findPayment(authorizeId, null).orElseThrow();
    assertEquals("CAPTURED", captured.status().name());
    assertEquals(5000, captured.capturedAmountMinor());
    assertNotNull(captured.capturedAt());
    assertEquals("PAYMENT_STATE_CONFLICT",
        assertThrows(ApiException.class, () -> repository.updateCapture(authorizeId, 5000)).code());

    String declineId = newIntent("merchant-demo-01", "Declined repository intent");
    repository.insertAttempt(
        declineId, 1, "DECLINED", "SIM-AUTH-decline", "SIMULATED_DECLINE", UUID.randomUUID().toString());
    repository.updateAuthorization(declineId, "DECLINED");
    assertEquals("DECLINED", repository.findPayment(declineId, null).orElseThrow().status().name());

    String voidId = newIntent("merchant-demo-02", "Operator void repository intent");
    repository.updateAuthorization(voidId, "AUTHORIZED");
    repository.updateVoid(voidId);
    assertEquals("VOIDED", repository.findPayment(voidId, null).orElseThrow().status().name());
    assertEquals("PAYMENT_STATE_CONFLICT",
        assertThrows(ApiException.class, () -> repository.updateVoid(voidId)).code());
    assertEquals("PAYMENT_STATE_CONFLICT",
        assertThrows(ApiException.class, () -> repository.updateAuthorization("missing", "DECLINED")).code());
  }

  @Test
  void boundsRefundsAndCalculatesPartialAndFullRefundStatesAtomically() {
    String intentId = newIntent("merchant-demo-01", "Refund repository intent");
    repository.updateAuthorization(intentId, "AUTHORIZED");
    repository.updateCapture(intentId, 5000);

    repository.insertRefund(intentId, 3000, "Partial synthetic refund", UUID.randomUUID().toString());
    assertTrue(repository.updateRefund(intentId, 3000));
    assertEquals("PARTIALLY_REFUNDED", repository.findPayment(intentId, null).orElseThrow().status().name());
    assertEquals(3000, repository.findPayment(intentId, null).orElseThrow().refundedAmountMinor());

    repository.insertRefund(intentId, 2000, "Final synthetic refund", UUID.randomUUID().toString());
    assertTrue(repository.updateRefund(intentId, 2000));
    assertEquals("REFUNDED", repository.findPayment(intentId, null).orElseThrow().status().name());
    assertFalse(repository.updateRefund(intentId, 1));
  }

  @Test
  void persistsSignedWebhookDataAndBuildsOptionalEventFilters() {
    String intentId = newIntent("merchant-demo-01", "Webhook repository intent");
    repository.updateAuthorization(intentId, "AUTHORIZED");
    repository.updateCapture(intentId, 5000);
    repository.insertOutboxEvent(intentId, "PAYMENT_CAPTURED", 5000);
    assertEquals(1, repository.countPendingWebhooks(intentId));
    String captureEventId = repository.findPendingWebhooks(intentId).get(0).eventId();
    String refundEventId = UUID.randomUUID().toString();

    ProviderWebhookPayload capture = new ProviderWebhookPayload(
        captureEventId, "PAYMENT_CAPTURED", intentId, 5000, Instant.now());
    ProviderWebhookPayload refund = new ProviderWebhookPayload(
        refundEventId, "PAYMENT_REFUNDED", intentId, 1500, Instant.now());
    repository.insertWebhook(capture, "a".repeat(64));
    repository.insertWebhook(refund, "b".repeat(64));
    assertEquals("a".repeat(64), repository.findWebhookHash(captureEventId).orElseThrow());
    assertTrue(repository.findWebhookHash("missing-event").isEmpty());
    assertEquals(5000, repository.sumWebhookAmount(intentId, "PAYMENT_CAPTURED"));
    assertEquals(1500, repository.sumWebhookAmount(intentId, "PAYMENT_REFUNDED"));
    assertEquals(0, repository.sumWebhookAmount(intentId, "UNKNOWN_EVENT"));
    assertEquals(2, repository.findWebhookEvents(null, null, 100).stream()
        .filter(event -> event.paymentIntentId().equals(intentId)).count());
    assertEquals(2, repository.findWebhookEvents("merchant-demo-01", null, 100).stream()
        .filter(event -> event.paymentIntentId().equals(intentId)).count());
    assertEquals(2, repository.findWebhookEvents(null, intentId, 100).size());
    assertEquals(2, repository.findWebhookEvents("merchant-demo-01", intentId, 100).size());
    assertTrue(repository.findWebhookEvents("merchant-demo-02", intentId, 100).isEmpty());

    repository.markOutboxDelivered(captureEventId);
    repository.markOutboxDelivered(captureEventId);
    assertEquals(0, repository.countPendingWebhooks(intentId));
    assertTrue(repository.findPendingWebhooks(intentId).isEmpty());
  }

  @Test
  void mapsReconciliationsAuditEventsAndMerchantScopedOverviewCounts() {
    String northstarId = newIntent("merchant-demo-01", "Reconciliation repository intent");
    String cedarId = newIntent("merchant-demo-02", "Other merchant audit intent");
    repository.updateAuthorization(northstarId, "AUTHORIZED");
    repository.updateCapture(northstarId, 4000);
    repository.insertOutboxEvent(northstarId, "PAYMENT_CAPTURED", 4000);
    String matchingId = repository.insertReconciliation(northstarId, 4000, 4000, 0, 0, "MATCHED");
    String mismatchId = repository.insertReconciliation(northstarId, 4000, 3000, 0, 0, "MISMATCH");

    assertEquals("MATCHED", repository.findReconciliation(matchingId).orElseThrow().result());
    assertTrue(repository.findReconciliation("missing-reconciliation").isEmpty());
    assertTrue(repository.findReconciliations("merchant-demo-01", 100).stream()
        .anyMatch(row -> row.id().equals(mismatchId)));
    assertTrue(repository.findReconciliations(null, 100).stream()
        .anyMatch(row -> row.id().equals(matchingId)));
    assertTrue(repository.findReconciliations("merchant-demo-02", 100).isEmpty());

    repository.insertAuditEvent(
        "user-merchant-01", "merchant-demo-01", "payment.capture.completed", "payment_intent", northstarId);
    repository.insertAuditEvent(
        "user-operator-01", null, "payment.reconciliation.completed", "payment_intent", cedarId);
    assertTrue(repository.findAuditEvents("merchant-demo-01", 100).stream()
        .anyMatch(event -> event.merchantId().equals("merchant-demo-01")));
    assertTrue(repository.findAuditEvents(null, 100).stream()
        .anyMatch(event -> event.merchantId() == null));

    var merchantOverview = repository.overview("merchant-demo-01");
    var platformOverview = repository.overview(null);
    assertTrue(merchantOverview.totalIntentCount() >= 1);
    assertTrue(platformOverview.totalIntentCount() >= merchantOverview.totalIntentCount());
    assertTrue(merchantOverview.pendingWebhookCount() >= 1);
    assertTrue(merchantOverview.reconciliationMismatchCount() >= 1);
  }

  private String newIntent(String merchantId, String description) {
    String id = UUID.randomUUID().toString();
    String idempotencyKey = "repository-create-" + id;
    repository.insertIntent(
        id,
        merchantId,
        5000,
        "USD",
        description,
        "APPROVE",
        idempotencyKey,
        "request-hash-" + id);
    return id;
  }

  private String idempotencyKeyFor(String intentId) {
    return jdbcTemplate.queryForObject(
        "SELECT idempotency_key FROM payment_intents WHERE id = ?", String.class, intentId);
  }
}
