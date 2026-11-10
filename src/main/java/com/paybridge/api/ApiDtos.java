package com.paybridge.api;

import com.paybridge.domain.AppRole;
import com.paybridge.domain.PaymentStatus;
import com.paybridge.domain.SimulationOutcome;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ApiDtos {
  private ApiDtos() {}

  public record WorkspaceProfileView(String id, String displayName, AppRole role) {}

  public record ProfileLoginRequest(
      @NotBlank @Pattern(regexp = "merchant-northstar|merchant-cedar|operator|auditor") String accountId) {}

  public record SessionUserView(String id, String displayName, AppRole role, String merchantId) {}

  public record SessionResponse(SessionUserView user, boolean localProfileSession) {}

  public record CreateIntentRequest(
      @Positive @Max(100_000_000) long amountMinor,
      @NotBlank @Pattern(regexp = "USD") String currency,
      @NotBlank @Size(max = 160) String description,
      @NotNull SimulationOutcome simulationOutcome) {}

  public record RefundRequest(
      @Positive @Max(100_000_000) long amountMinor,
      @NotBlank @Size(min = 4, max = 160) String reason) {}

  public record PaymentIntentView(
      String id,
      String merchantId,
      String merchantName,
      long amountMinor,
      String currency,
      String description,
      PaymentStatus status,
      SimulationOutcome simulationOutcome,
      long capturedAmountMinor,
      long refundedAmountMinor,
      int webhookEventCount,
      String latestReconciliationResult,
      String createdAt,
      String updatedAt,
      String authorizedAt,
      String capturedAt) {}

  public record OverviewView(
      long totalIntentCount,
      long capturedAmountMinor,
      long refundedAmountMinor,
      long awaitingAuthorizationCount,
      long declinedCount,
      long pendingWebhookCount,
      long reconciliationMismatchCount,
      long capturedIntentCount,
      long authorizedIntentCount) {}

  public record WebhookEventView(
      String eventId,
      String paymentIntentId,
      String eventType,
      long amountMinor,
      boolean signatureVerified,
      String receivedAt) {}

  public record ReconciliationView(
      String id,
      String paymentIntentId,
      long expectedCapturedMinor,
      long observedCapturedMinor,
      long expectedRefundedMinor,
      long observedRefundedMinor,
      String result,
      String reconciledAt) {}

  public record AuditEventView(
      String id,
      String actorId,
      String merchantId,
      String eventName,
      String entityType,
      String entityId,
      String createdAt) {}

  public record ProviderWebhookPayload(
      String eventId,
      String eventType,
      String intentId,
      long amountMinor,
      Instant occurredAt) {}

  public record WebhookAck(String eventId, boolean duplicate, boolean signatureVerified, String status) {}

  public record WebhookDeliveryResponse(List<WebhookAck> deliveries) {}

  public record ReconciliationResponse(ReconciliationView reconciliation) {}

  public record PaymentIntentResponse(PaymentIntentView paymentIntent, boolean duplicate) {}

  public record PaymentIntentsResponse(List<PaymentIntentView> paymentIntents) {}

  public record WebhookEventsResponse(List<WebhookEventView> webhookEvents) {}

  public record ReconciliationsResponse(List<ReconciliationView> reconciliations) {}

  public record AuditEventsResponse(List<AuditEventView> auditEvents) {}
}
