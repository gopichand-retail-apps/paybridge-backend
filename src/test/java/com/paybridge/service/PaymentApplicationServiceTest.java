package com.paybridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paybridge.api.ApiDtos.CreateIntentRequest;
import com.paybridge.api.ApiDtos.PaymentIntentView;
import com.paybridge.api.ApiDtos.ReconciliationView;
import com.paybridge.api.ApiDtos.RefundRequest;
import com.paybridge.api.ApiException;
import com.paybridge.domain.AppRole;
import com.paybridge.domain.DemoPrincipal;
import com.paybridge.domain.PaymentStatus;
import com.paybridge.domain.SimulationOutcome;
import com.paybridge.persistence.PaymentRepository;
import com.paybridge.persistence.PaymentRepository.ActionIdempotencyRecord;
import com.paybridge.persistence.PaymentRepository.CreateIdempotencyRecord;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PaymentApplicationServiceTest {
  private static final String INTENT_ID = "intent-test-001";
  private static final String KEY = "service-key-001";

  private PaymentRepository repository;
  private PaymentProviderSimulator provider;
  private WebhookDeliveryService webhookDelivery;
  private PaymentApplicationService service;

  private final DemoPrincipal merchant =
      new DemoPrincipal("user-merchant-01", "Alex Morgan", AppRole.MERCHANT, "merchant-demo-01");
  private final DemoPrincipal operator =
      new DemoPrincipal("user-operator-01", "Morgan Patel", AppRole.OPERATOR, null);
  private final DemoPrincipal auditor =
      new DemoPrincipal("user-auditor-01", "Sam Rivera", AppRole.AUDITOR, null);

  @BeforeEach
  void setUp() {
    repository = mock(PaymentRepository.class);
    provider = mock(PaymentProviderSimulator.class);
    webhookDelivery = mock(WebhookDeliveryService.class);
    service = new PaymentApplicationService(repository, provider, webhookDelivery);
    when(repository.findPayment(anyString(), nullable(String.class)))
        .thenReturn(Optional.of(intent(PaymentStatus.CREATED, SimulationOutcome.APPROVE, 0, 0)));
    when(repository.findCreateIdempotency(anyString(), anyString())).thenReturn(Optional.empty());
    when(repository.findActionIdempotency(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    when(repository.countAttempts(anyString())).thenReturn(0);
    when(repository.updateRefund(anyString(), anyLong())).thenReturn(true);
    when(repository.sumWebhookAmount(anyString(), anyString())).thenReturn(0L);
    when(repository.countPendingWebhooks(anyString())).thenReturn(0L);
    when(repository.insertReconciliation(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyString()))
        .thenReturn("reconciliation-test-001");
    when(repository.findReconciliation(anyString())).thenReturn(Optional.of(reconciliation("MATCHED")));
  }

  @Test
  void scopesOverviewAndPaymentListsToMerchantRole() {
    service.overview(merchant);
    service.overview(operator);
    service.listPayments(merchant, null, null, 1);
    service.listPayments(merchant, "all", "  ", 100);
    service.listPayments(merchant, "  ", null, 2);
    service.listPayments(merchant, " cApTuReD ", " order ", 25);

    verify(repository).overview("merchant-demo-01");
    verify(repository).overview(null);
    verify(repository).findPayments("merchant-demo-01", null, "", 1);
    verify(repository).findPayments("merchant-demo-01", null, "", 100);
    verify(repository).findPayments("merchant-demo-01", null, "", 2);
    verify(repository).findPayments("merchant-demo-01", "CAPTURED", "order", 25);
  }

  @Test
  void rejectsUnsupportedFiltersAndLimitsBeforeQuerying() {
    ApiException badLimit = assertThrows(
        ApiException.class, () -> service.listPayments(merchant, null, null, 0));
    assertEquals("INVALID_LIMIT", badLimit.code());
    assertEquals(HttpStatus.BAD_REQUEST, badLimit.status());
    assertThrows(ApiException.class, () -> service.listPayments(merchant, null, null, 101));
    assertEquals("INVALID_STATUS",
        assertThrows(ApiException.class, () -> service.listPayments(merchant, "unknown", null, 10)).code());
    assertEquals("INVALID_SEARCH",
        assertThrows(ApiException.class, () -> service.listPayments(merchant, null, "x".repeat(81), 10)).code());
    verify(repository, never()).findPayments(anyString(), nullable(String.class), anyString(), anyInt());
  }

  @Test
  void createsOnlyMerchantIntentsAndTrimsDescriptionsAndIdempotencyKeys() {
    CreateIntentRequest request = new CreateIntentRequest(
        12500, "USD", "  Synthetic order  ", SimulationOutcome.APPROVE);

    PaymentApplicationService.CreateOutcome outcome = service.createIntent(merchant, request, " " + KEY + " ");

    assertFalse(outcome.duplicate());
    assertEquals(INTENT_ID, outcome.paymentIntent().id());
    verify(repository).insertIntent(
        anyString(),
        eq("merchant-demo-01"),
        eq(12500L),
        eq("USD"),
        eq("Synthetic order"),
        eq("APPROVE"),
        eq(KEY),
        anyString());
    verify(repository).insertAuditEvent(
        eq("user-merchant-01"), eq("merchant-demo-01"), eq("payment_intent.created"),
        eq("payment_intent"), anyString());
  }

  @Test
  void replaysMatchingCreatesAndRejectsChangedRequestsAndUnsafeKeys() {
    CreateIntentRequest request = new CreateIntentRequest(12500, "USD", "Order", SimulationOutcome.APPROVE);
    String requestHash = Hashing.sha256("12500|USD|Order|APPROVE");
    when(repository.findCreateIdempotency("merchant-demo-01", KEY))
        .thenReturn(Optional.of(new CreateIdempotencyRecord(INTENT_ID, requestHash)));

    PaymentApplicationService.CreateOutcome replay = service.createIntent(merchant, request, KEY);

    assertTrue(replay.duplicate());
    verify(repository, never()).insertIntent(
        anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    when(repository.findCreateIdempotency("merchant-demo-01", KEY))
        .thenReturn(Optional.of(new CreateIdempotencyRecord(INTENT_ID, "different-hash")));
    assertEquals("IDEMPOTENCY_CONFLICT",
        assertThrows(ApiException.class, () -> service.createIntent(merchant, request, KEY)).code());
    assertEquals("IDEMPOTENCY_KEY_REQUIRED",
        assertThrows(ApiException.class, () -> service.createIntent(merchant, request, "short")).code());
    assertEquals("IDEMPOTENCY_KEY_REQUIRED",
        assertThrows(ApiException.class, () -> service.createIntent(merchant, request, null)).code());
    assertEquals("ROLE_REQUIRED",
        assertThrows(ApiException.class, () -> service.createIntent(auditor, request, KEY)).code());
  }

  @Test
  void authorizesApprovedAndDeclinedSimulationsAndReplaysTheSameActionSafely() {
    when(repository.findPayment(INTENT_ID, "merchant-demo-01"))
        .thenReturn(Optional.of(intent(PaymentStatus.CREATED, SimulationOutcome.APPROVE, 0, 0)));
    when(provider.authorize(SimulationOutcome.APPROVE))
        .thenReturn(new PaymentProviderSimulator.AuthorizationResult(true, "SIM-AUTH-1", null));
    service.authorize(INTENT_ID, KEY, merchant);
    verify(repository).insertAttempt(
        eq(INTENT_ID), eq(1), eq("APPROVED"), eq("SIM-AUTH-1"), eq(null), anyString());
    verify(repository).updateAuthorization(INTENT_ID, "AUTHORIZED");

    when(repository.findPayment(INTENT_ID, "merchant-demo-01"))
        .thenReturn(Optional.of(intent(PaymentStatus.CREATED, SimulationOutcome.DECLINE, 0, 0)));
    when(provider.authorize(SimulationOutcome.DECLINE))
        .thenReturn(new PaymentProviderSimulator.AuthorizationResult(false, "SIM-AUTH-2", "SIMULATED_DECLINE"));
    service.authorize(INTENT_ID, "decline-key-01", merchant);
    verify(repository).insertAttempt(
        eq(INTENT_ID), eq(1), eq("DECLINED"), eq("SIM-AUTH-2"), eq("SIMULATED_DECLINE"), anyString());
    verify(repository).updateAuthorization(INTENT_ID, "DECLINED");

    String hash = Hashing.sha256("AUTHORIZE|" + INTENT_ID);
    when(repository.findActionIdempotency("user-merchant-01", "AUTHORIZE", "replay-key-01"))
        .thenReturn(Optional.of(new ActionIdempotencyRecord(hash, INTENT_ID)));
    PaymentIntentView replay = service.authorize(INTENT_ID, "replay-key-01", merchant);
    assertEquals(INTENT_ID, replay.id());
    verify(provider).authorize(SimulationOutcome.APPROVE);
    verify(provider).authorize(SimulationOutcome.DECLINE);
    when(repository.findActionIdempotency("user-merchant-01", "AUTHORIZE", "replay-key-01"))
        .thenReturn(Optional.of(new ActionIdempotencyRecord("different", INTENT_ID)));
    assertEquals("IDEMPOTENCY_CONFLICT",
        assertThrows(ApiException.class, () -> service.authorize(INTENT_ID, "replay-key-01", merchant)).code());
  }

  @Test
  void enforcesAuthorizationRoleStateAndPaymentOwnership() {
    assertEquals("ROLE_REQUIRED",
        assertThrows(ApiException.class, () -> service.authorize(INTENT_ID, KEY, auditor)).code());
    when(repository.findPayment(INTENT_ID, "merchant-demo-01"))
        .thenReturn(Optional.of(intent(PaymentStatus.CAPTURED, SimulationOutcome.APPROVE, 12500, 0)));
    assertEquals("INVALID_PAYMENT_TRANSITION",
        assertThrows(ApiException.class, () -> service.authorize(INTENT_ID, KEY, merchant)).code());
    when(repository.findPayment("missing", "merchant-demo-01")).thenReturn(Optional.empty());
    assertEquals("PAYMENT_NOT_FOUND",
        assertThrows(ApiException.class, () -> service.getPayment("missing", merchant)).code());
    assertEquals("IDEMPOTENCY_KEY_REQUIRED",
        assertThrows(ApiException.class, () -> service.authorize(INTENT_ID, "bad key", merchant)).code());
  }

  @Test
  void capturesOnlyAuthorizedPaymentsAndWritesOutboxAndAuditRecords() {
    when(repository.findPayment(INTENT_ID, "merchant-demo-01"))
        .thenReturn(Optional.of(intent(PaymentStatus.AUTHORIZED, SimulationOutcome.APPROVE, 0, 0)));
    when(provider.captureReference()).thenReturn("SIM-CAP-1");

    service.capture(INTENT_ID, KEY, merchant);

    verify(repository).insertCapture(eq(INTENT_ID), eq(12500L), eq("SIM-CAP-1"), anyString());
    verify(repository).updateCapture(INTENT_ID, 12500);
    verify(repository).insertOutboxEvent(INTENT_ID, "PAYMENT_CAPTURED", 12500);
    verify(repository).insertActionIdempotency(
        "user-merchant-01", "CAPTURE", KEY, Hashing.sha256("CAPTURE|" + INTENT_ID), INTENT_ID);
    when(repository.findPayment(INTENT_ID, "merchant-demo-01"))
        .thenReturn(Optional.of(intent(PaymentStatus.CREATED, SimulationOutcome.APPROVE, 0, 0)));
    assertEquals("INVALID_PAYMENT_TRANSITION",
        assertThrows(ApiException.class, () -> service.capture(INTENT_ID, "capture-key-02", merchant)).code());
  }

  @Test
  void voidsOnlyAuthorizedPaymentsForOperatorsAndSupportsIdempotentReplay() {
    when(repository.findPayment(INTENT_ID, null))
        .thenReturn(Optional.of(intent(PaymentStatus.AUTHORIZED, SimulationOutcome.APPROVE, 0, 0)));

    PaymentIntentView voided = service.voidIntent(INTENT_ID, KEY, operator);

    assertEquals(INTENT_ID, voided.id());
    verify(repository).updateVoid(INTENT_ID);
    assertEquals("ROLE_REQUIRED",
        assertThrows(ApiException.class, () -> service.voidIntent(INTENT_ID, "void-key-02", merchant)).code());
    String hash = Hashing.sha256("VOID|" + INTENT_ID);
    when(repository.findActionIdempotency("user-operator-01", "VOID", "void-key-03"))
        .thenReturn(Optional.of(new ActionIdempotencyRecord(hash, INTENT_ID)));
    assertEquals(INTENT_ID, service.voidIntent(INTENT_ID, "void-key-03", operator).id());
  }

  @Test
  void recordsPartialAndFullRefundsAndRejectsUnsafeAmountsAndRaces() {
    when(repository.findPayment(INTENT_ID, null))
        .thenReturn(Optional.of(intent(PaymentStatus.CAPTURED, SimulationOutcome.APPROVE, 12500, 0)));
    RefundRequest partial = new RefundRequest(2500, "  Customer request  ");

    service.refund(INTENT_ID, partial, KEY, operator);

    verify(repository).insertRefund(eq(INTENT_ID), eq(2500L), eq("Customer request"), anyString());
    verify(repository).insertOutboxEvent(INTENT_ID, "PAYMENT_REFUNDED", 2500);
    when(repository.findPayment(INTENT_ID, null))
        .thenReturn(Optional.of(intent(PaymentStatus.PARTIALLY_REFUNDED, SimulationOutcome.APPROVE, 12500, 2500)));
    when(repository.updateRefund(INTENT_ID, 10000)).thenReturn(true);
    service.refund(INTENT_ID, new RefundRequest(10000, "Final request"), "refund-key-02", operator);

    when(repository.updateRefund(INTENT_ID, 1)).thenReturn(false);
    assertEquals("REFUND_EXCEEDS_CAPTURE",
        assertThrows(ApiException.class,
            () -> service.refund(
                INTENT_ID, new RefundRequest(1, "Race recovery"), "refund-key-03", operator)).code());
    assertEquals("REFUND_EXCEEDS_CAPTURE",
        assertThrows(ApiException.class,
            () -> service.refund(
                INTENT_ID, new RefundRequest(10001, "Over captured"), "refund-key-04", operator)).code());
    assertEquals("ROLE_REQUIRED",
        assertThrows(ApiException.class,
            () -> service.refund(INTENT_ID, partial, "refund-key-05", merchant)).code());
    when(repository.findPayment(INTENT_ID, null))
        .thenReturn(Optional.of(intent(PaymentStatus.AUTHORIZED, SimulationOutcome.APPROVE, 0, 0)));
    assertEquals("INVALID_PAYMENT_TRANSITION",
        assertThrows(ApiException.class,
            () -> service.refund(INTENT_ID, partial, "refund-key-06", operator)).code());
  }

  @Test
  void replaysRefundsOnlyForTheSameCommand() {
    RefundRequest request = new RefundRequest(2500, "Customer request");
    String hash = Hashing.sha256("REFUND|" + INTENT_ID + "|2500|Customer request");
    when(repository.findActionIdempotency("user-operator-01", "REFUND", "refund-replay-1"))
        .thenReturn(Optional.of(new ActionIdempotencyRecord(hash, INTENT_ID)));

    PaymentIntentView replay = service.refund(INTENT_ID, request, "refund-replay-1", operator);

    assertEquals(INTENT_ID, replay.id());
    verify(repository, never()).insertRefund(anyString(), anyLong(), anyString(), anyString());
    when(repository.findActionIdempotency("user-operator-01", "REFUND", "refund-replay-1"))
        .thenReturn(Optional.of(new ActionIdempotencyRecord("different", INTENT_ID)));
    assertEquals("IDEMPOTENCY_CONFLICT",
        assertThrows(ApiException.class, () -> service.refund(INTENT_ID, request, "refund-replay-1", operator)).code());
  }

  @Test
  void reconcilesMatchedPendingAndMismatchedTotals() {
    when(repository.findPayment(INTENT_ID, null))
        .thenReturn(Optional.of(intent(PaymentStatus.CAPTURED, SimulationOutcome.APPROVE, 12500, 2500)));
    when(repository.sumWebhookAmount(INTENT_ID, "PAYMENT_CAPTURED")).thenReturn(12500L);
    when(repository.sumWebhookAmount(INTENT_ID, "PAYMENT_REFUNDED")).thenReturn(2500L);
    when(repository.findReconciliation("reconciliation-test-001"))
        .thenReturn(Optional.of(reconciliation("MATCHED")));
    assertEquals("MATCHED", service.reconcile(INTENT_ID, KEY, operator).result());

    when(repository.countPendingWebhooks(INTENT_ID)).thenReturn(1L);
    when(repository.findReconciliation("reconciliation-test-001"))
        .thenReturn(Optional.of(reconciliation("PENDING")));
    assertEquals("PENDING", service.reconcile(INTENT_ID, "reconcile-key-02", operator).result());

    when(repository.countPendingWebhooks(INTENT_ID)).thenReturn(0L);
    when(repository.sumWebhookAmount(INTENT_ID, "PAYMENT_CAPTURED")).thenReturn(100L);
    when(repository.findReconciliation("reconciliation-test-001"))
        .thenReturn(Optional.of(reconciliation("MISMATCH")));
    assertEquals("MISMATCH", service.reconcile(INTENT_ID, "reconcile-key-03", operator).result());

    when(repository.sumWebhookAmount(INTENT_ID, "PAYMENT_CAPTURED")).thenReturn(12500L);
    when(repository.sumWebhookAmount(INTENT_ID, "PAYMENT_REFUNDED")).thenReturn(0L);
    assertEquals("MISMATCH", service.reconcile(INTENT_ID, "reconcile-key-04", operator).result());
  }

  @Test
  void handlesReconciliationReplayConflictsMissingRowsAndRoleDenial() {
    String hash = Hashing.sha256("RECONCILE|" + INTENT_ID);
    when(repository.findActionIdempotency("user-operator-01", "RECONCILE", "reconcile-replay-1"))
        .thenReturn(Optional.of(new ActionIdempotencyRecord(hash, "reconciliation-test-001")));
    assertEquals("MATCHED", service.reconcile(INTENT_ID, "reconcile-replay-1", operator).result());

    when(repository.findActionIdempotency("user-operator-01", "RECONCILE", "reconcile-replay-2"))
        .thenReturn(Optional.of(new ActionIdempotencyRecord("different", "reconciliation-test-001")));
    assertEquals("IDEMPOTENCY_CONFLICT",
        assertThrows(ApiException.class,
            () -> service.reconcile(INTENT_ID, "reconcile-replay-2", operator)).code());

    when(repository.findActionIdempotency("user-operator-01", "RECONCILE", "reconcile-replay-3"))
        .thenReturn(Optional.of(new ActionIdempotencyRecord(hash, "missing-reconciliation")));
    when(repository.findReconciliation("missing-reconciliation")).thenReturn(Optional.empty());
    assertEquals("RECONCILIATION_NOT_FOUND",
        assertThrows(ApiException.class,
            () -> service.reconcile(INTENT_ID, "reconcile-replay-3", operator)).code());
    assertEquals("ROLE_REQUIRED",
        assertThrows(ApiException.class, () -> service.reconcile(INTENT_ID, "reconcile-key-05", merchant)).code());
    assertEquals("IDEMPOTENCY_KEY_REQUIRED",
        assertThrows(ApiException.class, () -> service.reconcile(INTENT_ID, "no", operator)).code());

    when(repository.findActionIdempotency(anyString(), eq("RECONCILE"), eq("reconcile-key-06")))
        .thenReturn(Optional.empty());
    when(repository.findReconciliation("reconciliation-test-001")).thenReturn(Optional.empty());
    assertEquals("RECONCILIATION_UNAVAILABLE",
        assertThrows(ApiException.class,
            () -> service.reconcile(INTENT_ID, "reconcile-key-06", operator)).code());
  }

  private PaymentIntentView intent(
      PaymentStatus status,
      SimulationOutcome outcome,
      long captured,
      long refunded) {
    return new PaymentIntentView(
        INTENT_ID,
        "merchant-demo-01",
        "Northstar Goods",
        12500,
        "USD",
        "Synthetic order",
        status,
        outcome,
        captured,
        refunded,
        0,
        null,
        "2026-10-30T12:00:00Z",
        "2026-10-30T12:00:00Z",
        null,
        null);
  }

  private ReconciliationView reconciliation(String result) {
    return new ReconciliationView(
        "reconciliation-test-001",
        INTENT_ID,
        12500,
        12500,
        0,
        0,
        result,
        "2026-10-30T12:00:00Z");
  }
}
