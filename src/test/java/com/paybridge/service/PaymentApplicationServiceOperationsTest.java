package com.paybridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paybridge.api.ApiDtos.PaymentIntentView;
import com.paybridge.api.ApiDtos.ReconciliationView;
import com.paybridge.api.ApiException;
import com.paybridge.domain.AppRole;
import com.paybridge.domain.DemoPrincipal;
import com.paybridge.domain.PaymentStatus;
import com.paybridge.domain.SimulationOutcome;
import com.paybridge.persistence.PaymentRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentApplicationServiceOperationsTest {
  private static final String INTENT_ID = "intent-operations-001";
  private final DemoPrincipal merchant =
      new DemoPrincipal("user-merchant-01", "Alex Morgan", AppRole.MERCHANT, "merchant-demo-01");
  private final DemoPrincipal operator =
      new DemoPrincipal("user-operator-01", "Morgan Patel", AppRole.OPERATOR, null);
  private final DemoPrincipal auditor =
      new DemoPrincipal("user-auditor-01", "Sam Rivera", AppRole.AUDITOR, null);

  private PaymentRepository repository;
  private WebhookDeliveryService webhookDelivery;
  private PaymentApplicationService service;

  @BeforeEach
  void setUp() {
    repository = mock(PaymentRepository.class);
    PaymentProviderSimulator provider = mock(PaymentProviderSimulator.class);
    webhookDelivery = mock(WebhookDeliveryService.class);
    service = new PaymentApplicationService(repository, provider, webhookDelivery);
    when(repository.findPayment(anyString(), nullable(String.class)))
        .thenReturn(Optional.of(intent(PaymentStatus.CREATED, 0, 0)));
    when(repository.findActionIdempotency(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    when(repository.sumWebhookAmount(anyString(), anyString())).thenReturn(0L);
    when(repository.countPendingWebhooks(anyString())).thenReturn(0L);
    when(repository.insertReconciliation(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyString()))
        .thenReturn("reconciliation-operations-001");
    when(repository.findReconciliation(anyString())).thenReturn(Optional.of(reconciliation("MATCHED")));
  }

  @Test
  void scopesWebhookReconciliationAndAuditQueriesAndValidatesLimits() {
    service.webhookEvents(merchant, null, 1);
    service.webhookEvents(operator, INTENT_ID, 100);
    service.reconciliations(merchant, 2);
    service.reconciliations(operator, 3);
    service.auditEvents(merchant, 4);
    service.auditEvents(operator, 5);

    verify(repository).findWebhookEvents("merchant-demo-01", null, 1);
    verify(repository).findWebhookEvents(null, INTENT_ID, 100);
    verify(repository).findReconciliations("merchant-demo-01", 2);
    verify(repository).findReconciliations(null, 3);
    verify(repository).findAuditEvents("merchant-demo-01", 4);
    verify(repository).findAuditEvents(null, 5);

    when(repository.findPayment("missing", "merchant-demo-01")).thenReturn(Optional.empty());
    assertEquals("PAYMENT_NOT_FOUND",
        assertThrows(ApiException.class, () -> service.webhookEvents(merchant, "missing", 1)).code());
    assertEquals("INVALID_LIMIT",
        assertThrows(ApiException.class, () -> service.webhookEvents(merchant, null, 0)).code());
    assertEquals("INVALID_LIMIT",
        assertThrows(ApiException.class, () -> service.reconciliations(operator, 101)).code());
    assertEquals("INVALID_LIMIT",
        assertThrows(ApiException.class, () -> service.auditEvents(operator, 0)).code());
  }

  @Test
  void deliversOnlyCapturedOrRefundedEventsForMutableRoles() {
    when(repository.findPayment(INTENT_ID, "merchant-demo-01"))
        .thenReturn(Optional.of(intent(PaymentStatus.CAPTURED, 12500, 0)));
    when(webhookDelivery.deliverPending(INTENT_ID)).thenReturn(List.of());
    assertTrue(service.deliverWebhooks(INTENT_ID, merchant).isEmpty());

    when(repository.findPayment(INTENT_ID, null))
        .thenReturn(Optional.of(intent(PaymentStatus.PARTIALLY_REFUNDED, 12500, 1000)));
    assertTrue(service.deliverWebhooks(INTENT_ID, operator).isEmpty());
    when(repository.findPayment(INTENT_ID, "merchant-demo-01"))
        .thenReturn(Optional.of(intent(PaymentStatus.REFUNDED, 12500, 12500)));
    assertTrue(service.deliverWebhooks(INTENT_ID, merchant).isEmpty());
    when(repository.findPayment(INTENT_ID, "merchant-demo-01"))
        .thenReturn(Optional.of(intent(PaymentStatus.CREATED, 0, 0)));
    assertEquals("INVALID_PAYMENT_TRANSITION",
        assertThrows(ApiException.class, () -> service.deliverWebhooks(INTENT_ID, merchant)).code());
    when(repository.findPayment(INTENT_ID, null))
        .thenReturn(Optional.of(intent(PaymentStatus.CAPTURED, 12500, 0)));
    assertEquals("ROLE_REQUIRED",
        assertThrows(ApiException.class, () -> service.deliverWebhooks(INTENT_ID, auditor)).code());
    verify(webhookDelivery, times(3)).deliverPending(INTENT_ID);
  }

  private PaymentIntentView intent(PaymentStatus status, long captured, long refunded) {
    return new PaymentIntentView(
        INTENT_ID,
        "merchant-demo-01",
        "Northstar Goods",
        12500,
        "USD",
        "Synthetic operations test",
        status,
        SimulationOutcome.APPROVE,
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
        "reconciliation-operations-001",
        INTENT_ID,
        12500,
        12500,
        0,
        0,
        result,
        "2026-10-30T12:00:00Z");
  }
}
