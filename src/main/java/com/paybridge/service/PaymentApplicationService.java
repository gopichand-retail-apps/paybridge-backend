package com.paybridge.service;

import com.paybridge.api.ApiDtos.AuditEventView;
import com.paybridge.api.ApiDtos.CreateIntentRequest;
import com.paybridge.api.ApiDtos.OverviewView;
import com.paybridge.api.ApiDtos.PaymentIntentView;
import com.paybridge.api.ApiDtos.ReconciliationView;
import com.paybridge.api.ApiDtos.RefundRequest;
import com.paybridge.api.ApiDtos.WebhookAck;
import com.paybridge.api.ApiDtos.WebhookEventView;
import com.paybridge.api.ApiException;
import com.paybridge.domain.AppRole;
import com.paybridge.domain.DemoPrincipal;
import com.paybridge.domain.PaymentStatus;
import com.paybridge.domain.SimulationOutcome;
import com.paybridge.persistence.PaymentRepository;
import com.paybridge.persistence.PaymentRepository.ActionIdempotencyRecord;
import com.paybridge.persistence.PaymentRepository.CreateIdempotencyRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentApplicationService {
  private static final Logger LOGGER = LoggerFactory.getLogger(PaymentApplicationService.class);
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");

  private final PaymentRepository paymentRepository;
  private final PaymentProviderSimulator providerSimulator;
  private final WebhookDeliveryService webhookDeliveryService;

  public PaymentApplicationService(
      PaymentRepository paymentRepository,
      PaymentProviderSimulator providerSimulator,
      WebhookDeliveryService webhookDeliveryService) {
    this.paymentRepository = paymentRepository;
    this.providerSimulator = providerSimulator;
    this.webhookDeliveryService = webhookDeliveryService;
  }

  @Transactional(readOnly = true)
  public OverviewView overview(DemoPrincipal principal) {
    return paymentRepository.overview(merchantScope(principal));
  }

  @Transactional(readOnly = true)
  public List<PaymentIntentView> listPayments(
      DemoPrincipal principal,
      String statusFilter,
      String search,
      int limit) {
    if (limit < 1 || limit > 100) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT", "Choose a limit between 1 and 100");
    }
    String status = normalizeStatus(statusFilter);
    String safeSearch = search == null ? "" : search.trim();
    if (safeSearch.length() > 80) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH", "Search must be 80 characters or fewer");
    }
    return paymentRepository.findPayments(merchantScope(principal), status, safeSearch, limit);
  }

  @Transactional(readOnly = true)
  public PaymentIntentView getPayment(String intentId, DemoPrincipal principal) {
    return requireAccessible(intentId, principal);
  }

  @Transactional
  public CreateOutcome createIntent(
      DemoPrincipal principal,
      CreateIntentRequest request,
      String rawIdempotencyKey) {
    requireRole(principal, AppRole.MERCHANT);
    String key = requireIdempotencyKey(rawIdempotencyKey);
    String requestHash = Hashing.sha256(String.join("|",
        Long.toString(request.amountMinor()), request.currency(), request.description().trim(),
        request.simulationOutcome().name()));
    Optional<CreateIdempotencyRecord> existing = paymentRepository.findCreateIdempotency(
        principal.merchantId(), key);
    if (existing.isPresent()) {
      if (!existing.get().requestHash().equals(requestHash)) {
        throw idempotencyConflict();
      }
      return new CreateOutcome(requireAccessible(existing.get().intentId(), principal), true);
    }

    String intentId = UUID.randomUUID().toString();
    paymentRepository.insertIntent(intentId, principal.merchantId(), request.amountMinor(), request.currency(),
        request.description().trim(), request.simulationOutcome().name(), key, requestHash);
    paymentRepository.insertAuditEvent(principal.id(), principal.merchantId(),
        "payment_intent.created", "payment_intent", intentId);
    LOGGER.info("eventName=paybridge.payment_intent.created outcome=success");
    return new CreateOutcome(requireAccessible(intentId, principal), false);
  }

  @Transactional
  public PaymentIntentView authorize(String intentId, String rawKey, DemoPrincipal principal) {
    String key = requireIdempotencyKey(rawKey);
    String requestHash = Hashing.sha256("AUTHORIZE|" + intentId);
    Optional<PaymentIntentView> replay = replayPaymentAction(principal, "AUTHORIZE", key, requestHash);
    if (replay.isPresent()) return replay.get();
    PaymentIntentView intent = requireAccessible(intentId, principal);
    requireMutableRole(principal);
    requireStatus(intent, PaymentStatus.CREATED);

    PaymentProviderSimulator.AuthorizationResult result = providerSimulator.authorize(intent.simulationOutcome());
    int attemptNumber = paymentRepository.countAttempts(intentId) + 1;
    paymentRepository.insertAttempt(intentId, attemptNumber, result.approved() ? "APPROVED" : "DECLINED",
        result.providerReference(), result.declineCode(), UUID.randomUUID().toString());
    paymentRepository.updateAuthorization(intentId, result.approved() ? "AUTHORIZED" : "DECLINED");
    rememberAction(principal, "AUTHORIZE", key, requestHash, intentId);
    recordAudit(principal, intent, result.approved()
        ? "payment.authorization.approved" : "payment.authorization.declined");
    LOGGER.info("eventName=paybridge.authorization.completed outcome={}", result.approved() ? "approved" : "declined");
    return requireAccessible(intentId, principal);
  }

  @Transactional
  public PaymentIntentView capture(String intentId, String rawKey, DemoPrincipal principal) {
    String key = requireIdempotencyKey(rawKey);
    String requestHash = Hashing.sha256("CAPTURE|" + intentId);
    Optional<PaymentIntentView> replay = replayPaymentAction(principal, "CAPTURE", key, requestHash);
    if (replay.isPresent()) return replay.get();
    PaymentIntentView intent = requireAccessible(intentId, principal);
    requireMutableRole(principal);
    requireStatus(intent, PaymentStatus.AUTHORIZED);

    paymentRepository.insertCapture(intentId, intent.amountMinor(), providerSimulator.captureReference(),
        UUID.randomUUID().toString());
    paymentRepository.updateCapture(intentId, intent.amountMinor());
    paymentRepository.insertOutboxEvent(intentId, "PAYMENT_CAPTURED", intent.amountMinor());
    rememberAction(principal, "CAPTURE", key, requestHash, intentId);
    recordAudit(principal, intent, "payment.capture.completed");
    LOGGER.info("eventName=paybridge.capture.completed outcome=success");
    return requireAccessible(intentId, principal);
  }

  @Transactional
  public PaymentIntentView voidIntent(String intentId, String rawKey, DemoPrincipal principal) {
    String key = requireIdempotencyKey(rawKey);
    String requestHash = Hashing.sha256("VOID|" + intentId);
    Optional<PaymentIntentView> replay = replayPaymentAction(principal, "VOID", key, requestHash);
    if (replay.isPresent()) return replay.get();
    PaymentIntentView intent = requireAccessible(intentId, principal);
    requireRole(principal, AppRole.OPERATOR);
    requireStatus(intent, PaymentStatus.AUTHORIZED);

    paymentRepository.updateVoid(intentId);
    rememberAction(principal, "VOID", key, requestHash, intentId);
    recordAudit(principal, intent, "payment.void.completed");
    LOGGER.info("eventName=paybridge.void.completed outcome=success");
    return requireAccessible(intentId, principal);
  }

  @Transactional
  public PaymentIntentView refund(
      String intentId,
      RefundRequest request,
      String rawKey,
      DemoPrincipal principal) {
    String key = requireIdempotencyKey(rawKey);
    String requestHash = Hashing.sha256(
        "REFUND|" + intentId + "|" + request.amountMinor() + "|" + request.reason().trim());
    Optional<PaymentIntentView> replay = replayPaymentAction(principal, "REFUND", key, requestHash);
    if (replay.isPresent()) return replay.get();
    PaymentIntentView intent = requireAccessible(intentId, principal);
    requireRole(principal, AppRole.OPERATOR);
    if (intent.status() != PaymentStatus.CAPTURED && intent.status() != PaymentStatus.PARTIALLY_REFUNDED) {
      throw invalidTransition("Only captured payments can be refunded");
    }
    long remaining = intent.capturedAmountMinor() - intent.refundedAmountMinor();
    if (request.amountMinor() > remaining) {
      throw new ApiException(
          HttpStatus.CONFLICT, "REFUND_EXCEEDS_CAPTURE", "Refund exceeds the remaining captured amount");
    }
    paymentRepository.insertRefund(
        intentId, request.amountMinor(), request.reason().trim(), UUID.randomUUID().toString());
    if (!paymentRepository.updateRefund(intentId, request.amountMinor())) {
      throw new ApiException(HttpStatus.CONFLICT, "REFUND_EXCEEDS_CAPTURE",
          "Refund exceeds the remaining captured amount");
    }
    paymentRepository.insertOutboxEvent(intentId, "PAYMENT_REFUNDED", request.amountMinor());
    rememberAction(principal, "REFUND", key, requestHash, intentId);
    recordAudit(principal, intent, "payment.refund.recorded");
    LOGGER.info("eventName=paybridge.refund.completed outcome=success");
    return requireAccessible(intentId, principal);
  }

  @Transactional
  public ReconciliationView reconcile(String intentId, String rawKey, DemoPrincipal principal) {
    String key = requireIdempotencyKey(rawKey);
    String requestHash = Hashing.sha256("RECONCILE|" + intentId);
    Optional<ActionIdempotencyRecord> replay = paymentRepository.findActionIdempotency(
        principal.id(), "RECONCILE", key);
    if (replay.isPresent()) {
      if (!replay.get().requestHash().equals(requestHash)) throw idempotencyConflict();
      return paymentRepository.findReconciliation(replay.get().entityId())
          .orElseThrow(() -> new ApiException(
              HttpStatus.NOT_FOUND, "RECONCILIATION_NOT_FOUND", "Reconciliation was not found"));
    }
    PaymentIntentView intent = requireAccessible(intentId, principal);
    requireRole(principal, AppRole.OPERATOR);
    long observedCaptured = paymentRepository.sumWebhookAmount(intentId, "PAYMENT_CAPTURED");
    long observedRefunded = paymentRepository.sumWebhookAmount(intentId, "PAYMENT_REFUNDED");
    boolean pending = paymentRepository.countPendingWebhooks(intentId) > 0;
    String result = pending ? "PENDING" :
        observedCaptured == intent.capturedAmountMinor() && observedRefunded == intent.refundedAmountMinor()
            ? "MATCHED" : "MISMATCH";
    String reconciliationId = paymentRepository.insertReconciliation(intentId, intent.capturedAmountMinor(),
        observedCaptured, intent.refundedAmountMinor(), observedRefunded, result);
    paymentRepository.insertActionIdempotency(principal.id(), "RECONCILE", key, requestHash, reconciliationId);
    recordAudit(principal, intent, "payment.reconciliation.completed");
    LOGGER.info("eventName=paybridge.reconciliation.completed outcome={}", result.toLowerCase());
    return paymentRepository.findReconciliation(reconciliationId)
        .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
            "RECONCILIATION_UNAVAILABLE", "Reconciliation could not be loaded"));
  }

  @Transactional(readOnly = true)
  public List<WebhookEventView> webhookEvents(DemoPrincipal principal, String intentId, int limit) {
    if (intentId != null) requireAccessible(intentId, principal);
    return paymentRepository.findWebhookEvents(merchantScope(principal), intentId, checkedLimit(limit));
  }

  @Transactional(readOnly = true)
  public List<ReconciliationView> reconciliations(DemoPrincipal principal, int limit) {
    return paymentRepository.findReconciliations(merchantScope(principal), checkedLimit(limit));
  }

  @Transactional(readOnly = true)
  public List<AuditEventView> auditEvents(DemoPrincipal principal, int limit) {
    return paymentRepository.findAuditEvents(merchantScope(principal), checkedLimit(limit));
  }

  public List<WebhookAck> deliverWebhooks(String intentId, DemoPrincipal principal) {
    PaymentIntentView intent = requireAccessible(intentId, principal);
    requireMutableRole(principal);
    if (intent.status() != PaymentStatus.CAPTURED && intent.status() != PaymentStatus.PARTIALLY_REFUNDED
        && intent.status() != PaymentStatus.REFUNDED) {
      throw invalidTransition("Only captured payments have webhook events to deliver");
    }
    return webhookDeliveryService.deliverPending(intentId);
  }

  private Optional<PaymentIntentView> replayPaymentAction(
      DemoPrincipal principal,
      String operation,
      String key,
      String requestHash) {
    Optional<ActionIdempotencyRecord> record = paymentRepository.findActionIdempotency(
        principal.id(), operation, key);
    if (record.isEmpty()) return Optional.empty();
    if (!record.get().requestHash().equals(requestHash)) throw idempotencyConflict();
    return Optional.of(requireAccessible(record.get().entityId(), principal));
  }

  private void rememberAction(
      DemoPrincipal principal,
      String operation,
      String key,
      String requestHash,
      String entityId) {
    paymentRepository.insertActionIdempotency(principal.id(), operation, key, requestHash, entityId);
  }

  private PaymentIntentView requireAccessible(String intentId, DemoPrincipal principal) {
    return paymentRepository.findPayment(intentId, merchantScope(principal))
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment was not found"));
  }

  private void recordAudit(DemoPrincipal principal, PaymentIntentView intent, String eventName) {
    paymentRepository.insertAuditEvent(principal.id(), intent.merchantId(), eventName, "payment_intent", intent.id());
  }

  private void requireStatus(PaymentIntentView intent, PaymentStatus expected) {
    if (intent.status() != expected) throw invalidTransition("Payment is not in the required state");
  }

  private void requireMutableRole(DemoPrincipal principal) {
    if (principal.role() != AppRole.MERCHANT && principal.role() != AppRole.OPERATOR) {
      throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_REQUIRED", "This action requires a merchant or operator role");
    }
  }

  private void requireRole(DemoPrincipal principal, AppRole required) {
    if (principal.role() != required) {
      throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_REQUIRED", "This action requires an elevated role");
    }
  }

  private String merchantScope(DemoPrincipal principal) {
    return principal.role() == AppRole.MERCHANT ? principal.merchantId() : null;
  }

  private String normalizeStatus(String status) {
    if (status == null || status.isBlank() || status.equalsIgnoreCase("all")) return null;
    try {
      return PaymentStatus.valueOf(status.trim().toUpperCase()).name();
    } catch (IllegalArgumentException exception) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Choose a supported payment status");
    }
  }

  private int checkedLimit(int limit) {
    if (limit < 1 || limit > 100) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT", "Choose a limit between 1 and 100");
    }
    return limit;
  }

  private String requireIdempotencyKey(String value) {
    if (value == null || !IDEMPOTENCY_KEY.matcher(value.trim()).matches()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
          "Provide a valid Idempotency-Key header");
    }
    return value.trim();
  }

  private ApiException invalidTransition(String message) {
    return new ApiException(HttpStatus.CONFLICT, "INVALID_PAYMENT_TRANSITION", message);
  }

  private ApiException idempotencyConflict() {
    return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
        "That idempotency key was already used for a different request");
  }

  public record CreateOutcome(PaymentIntentView paymentIntent, boolean duplicate) {}
}