package com.paybridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paybridge.api.ApiDtos.PaymentIntentView;
import com.paybridge.api.ApiDtos.ProviderWebhookPayload;
import com.paybridge.api.ApiDtos.WebhookAck;
import com.paybridge.api.ApiException;
import com.paybridge.domain.PaymentStatus;
import com.paybridge.domain.SimulationOutcome;
import com.paybridge.persistence.PaymentRepository;
import com.paybridge.persistence.PaymentRepository.PendingWebhook;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WebhookServicesTest {
  private static final String SECRET = "a".repeat(32);
  private static final String INTENT_ID = "intent-webhook-test";
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private PaymentRepository repository;
  private WebhookSignatureService signatureService;
  private WebhookReceiverService receiverService;

  @BeforeEach
  void setUp() {
    repository = mock(PaymentRepository.class);
    signatureService = new WebhookSignatureService(SECRET, true);
    receiverService = new WebhookReceiverService(objectMapper, signatureService, repository);
    when(repository.findWebhookHash(anyString())).thenReturn(Optional.empty());
    when(repository.findPayment(INTENT_ID, null)).thenReturn(Optional.of(intent(12500, 2500)));
  }

  @Test
  void signsAndVerifiesPayloadsWithConstantTimeDigestComparisonInputs() {
    byte[] body = "{\"eventId\":\"safe-event\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String signature = signatureService.sign(body);

    assertTrue(signature.startsWith("sha256="));
    assertTrue(signatureService.verify(body, signature));
    assertFalse(signatureService.verify(body, signature + "00"));
    assertFalse(signatureService.verify(body, "invalid=" + signature));
    assertFalse(signatureService.verify(body, "sha256=not-hex"));
    assertFalse(signatureService.verify(body, null));
  }

  @Test
  void enforcesConfiguredSecretRequirementsAndGeneratesADevelopmentKeyWhenAllowed() {
    assertThrows(IllegalStateException.class, () -> new WebhookSignatureService("", true));
    assertThrows(IllegalStateException.class, () -> new WebhookSignatureService("short", false));

    WebhookSignatureService local = new WebhookSignatureService("", false);
    byte[] body = { 1, 2, 3 };
    assertTrue(local.verify(body, local.sign(body)));
  }

  @Test
  void rejectsEmptyOversizedUnsignedAndMalformedWebhookBodies() throws Exception {
    assertEquals("INVALID_WEBHOOK",
        assertThrows(ApiException.class, () -> receiverService.receive(new byte[0], null)).code());
    assertEquals("INVALID_WEBHOOK",
        assertThrows(ApiException.class, () -> receiverService.receive(new byte[16_385], "sha256=00")).code());

    byte[] valid = serialize(payload("safe-event-01", "PAYMENT_CAPTURED", 12500, Instant.now()));
    assertEquals("INVALID_WEBHOOK_SIGNATURE",
        assertThrows(ApiException.class, () -> receiverService.receive(valid, "sha256=00")).code());

    byte[] malformed = "{not-json}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertEquals("INVALID_WEBHOOK",
        assertThrows(ApiException.class,
            () -> receiverService.receive(malformed, signatureService.sign(malformed))).code());
  }

  @Test
  void validatesEventFieldsAndRejectsStaleOrFutureWebhookTimestamps() throws Exception {
    List<ProviderWebhookPayload> invalidPayloads = List.of(
        payload("unsafe/id", "PAYMENT_CAPTURED", 12500, Instant.now()),
        payload("safe-event-02", "PAYMENT_CAPTURED", 12500, Instant.now(), ""),
        payload("safe-event-03", "PAYMENT_CAPTURED", 0, Instant.now()),
        payload("safe-event-04", "PAYMENT_CAPTURED", 12500, null),
        payload("safe-event-05", "UNKNOWN_EVENT", 12500, Instant.now()),
        new ProviderWebhookPayload("safe-event-06", null, INTENT_ID, 12500, Instant.now()));

    for (ProviderWebhookPayload invalid : invalidPayloads) {
      byte[] raw = serialize(invalid);
      assertEquals("INVALID_WEBHOOK",
          assertThrows(ApiException.class,
              () -> receiverService.receive(raw, signatureService.sign(raw))).code());
    }

    ProviderWebhookPayload missingId = new ProviderWebhookPayload(
        null, "PAYMENT_CAPTURED", INTENT_ID, 12500, Instant.now());
    byte[] missingIdBody = serialize(missingId);
    assertEquals("INVALID_WEBHOOK",
        assertThrows(ApiException.class,
            () -> receiverService.receive(missingIdBody, signatureService.sign(missingIdBody))).code());

    assertStale(payload("stale-event-01", "PAYMENT_CAPTURED", 12500, Instant.now().minus(6, ChronoUnit.MINUTES)));
    assertStale(payload("stale-event-02", "PAYMENT_CAPTURED", 12500, Instant.now().plus(6, ChronoUnit.MINUTES)));
  }

  @Test
  void acceptsSignedEventsOnceAndRejectsReusedIdsWithDifferentPayloads() throws Exception {
    ProviderWebhookPayload payload = payload("event-captured-01", "PAYMENT_CAPTURED", 12500, Instant.now());
    byte[] raw = serialize(payload);
    String hash = Hashing.sha256(raw);

    when(repository.findWebhookHash(payload.eventId())).thenReturn(Optional.of(hash));
    WebhookAck duplicate = receiverService.receive(raw, signatureService.sign(raw));
    assertTrue(duplicate.duplicate());
    assertEquals("DUPLICATE", duplicate.status());
    verify(repository, never()).insertWebhook(any(), anyString());

    when(repository.findWebhookHash(payload.eventId())).thenReturn(Optional.of("different-payload-hash"));
    assertEquals("WEBHOOK_EVENT_CONFLICT",
        assertThrows(ApiException.class,
            () -> receiverService.receive(raw, signatureService.sign(raw))).code());
  }

  @Test
  void persistsCapturedAndRefundedEventsOnlyWhenTheServerLedgerAllowsTheirAmounts() throws Exception {
    ProviderWebhookPayload capture = payload("event-captured-02", "PAYMENT_CAPTURED", 12500, Instant.now());
    byte[] captureBody = serialize(capture);
    WebhookAck delivered = receiverService.receive(captureBody, signatureService.sign(captureBody));
    assertFalse(delivered.duplicate());
    assertTrue(delivered.signatureVerified());
    assertEquals("DELIVERED", delivered.status());
    verify(repository).insertWebhook(capture, Hashing.sha256(captureBody));
    verify(repository).markOutboxDelivered(capture.eventId());

    ProviderWebhookPayload refund = payload("event-refunded-01", "PAYMENT_REFUNDED", 2500, Instant.now());
    byte[] refundBody = serialize(refund);
    assertEquals("DELIVERED", receiverService.receive(refundBody, signatureService.sign(refundBody)).status());

    ProviderWebhookPayload excessiveCapture =
        payload("event-captured-03", "PAYMENT_CAPTURED", 12501, Instant.now());
    assertAmountMismatch(excessiveCapture);
    ProviderWebhookPayload excessiveRefund =
        payload("event-refunded-02", "PAYMENT_REFUNDED", 2501, Instant.now());
    assertAmountMismatch(excessiveRefund);

    when(repository.findPayment("missing-intent", null)).thenReturn(Optional.empty());
    ProviderWebhookPayload missingIntent =
        payload("event-missing-01", "PAYMENT_CAPTURED", 1, Instant.now(), "missing-intent");
    byte[] missingBody = serialize(missingIntent);
    assertEquals("PAYMENT_NOT_FOUND",
        assertThrows(ApiException.class,
            () -> receiverService.receive(missingBody, signatureService.sign(missingBody))).code());
  }

  @Test
  void deliversOnlyPendingOutboxEventsAndStopsSafelyOnSerializationFailure() throws Exception {
    PaymentRepository pendingRepository = mock(PaymentRepository.class);
    WebhookSignatureService signer = new WebhookSignatureService(SECRET, true);
    WebhookReceiverService receiver = mock(WebhookReceiverService.class);
    WebhookDeliveryService delivery = new WebhookDeliveryService(
        objectMapper, signer, receiver, pendingRepository);
    PendingWebhook pending = new PendingWebhook("event-outbox-01", INTENT_ID, "PAYMENT_CAPTURED", 12500);
    when(pendingRepository.findPendingWebhooks(INTENT_ID)).thenReturn(List.of(pending));
    when(receiver.receive(any(byte[].class), anyString()))
        .thenReturn(new WebhookAck(pending.eventId(), false, true, "DELIVERED"));

    List<WebhookAck> result = delivery.deliverPending(INTENT_ID);

    assertEquals(1, result.size());
    assertEquals("DELIVERED", result.get(0).status());
    verify(receiver).receive(any(byte[].class), anyString());
    when(pendingRepository.findPendingWebhooks(INTENT_ID)).thenReturn(List.of());
    assertTrue(delivery.deliverPending(INTENT_ID).isEmpty());

    ObjectMapper failingMapper = mock(ObjectMapper.class);
    when(failingMapper.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("serialization failed") {});
    WebhookDeliveryService failingDelivery = new WebhookDeliveryService(
        failingMapper, signer, receiver, pendingRepository);
    when(pendingRepository.findPendingWebhooks(INTENT_ID)).thenReturn(List.of(pending));
    ApiException failure = assertThrows(ApiException.class, () -> failingDelivery.deliverPending(INTENT_ID));
    assertEquals("WEBHOOK_SERIALIZATION_FAILED", failure.code());
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failure.status());
  }

  @Test
  void hashesTextAndRawPayloadsConsistently() {
    assertEquals(Hashing.sha256("same-content"), Hashing.sha256("same-content".getBytes(
        java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals(64, Hashing.sha256("same-content").length());
  }

  private void assertStale(ProviderWebhookPayload payload) throws Exception {
    byte[] raw = serialize(payload);
    assertEquals("STALE_WEBHOOK",
        assertThrows(ApiException.class,
            () -> receiverService.receive(raw, signatureService.sign(raw))).code());
  }

  private void assertAmountMismatch(ProviderWebhookPayload payload) throws Exception {
    byte[] raw = serialize(payload);
    assertEquals("WEBHOOK_AMOUNT_MISMATCH",
        assertThrows(ApiException.class,
            () -> receiverService.receive(raw, signatureService.sign(raw))).code());
  }

  private byte[] serialize(ProviderWebhookPayload payload) throws Exception {
    return objectMapper.writeValueAsBytes(payload);
  }

  private ProviderWebhookPayload payload(String eventId, String eventType, long amount, Instant occurredAt) {
    return payload(eventId, eventType, amount, occurredAt, INTENT_ID);
  }

  private ProviderWebhookPayload payload(
      String eventId,
      String eventType,
      long amount,
      Instant occurredAt,
      String intentId) {
    return new ProviderWebhookPayload(eventId, eventType, intentId, amount, occurredAt);
  }

  private PaymentIntentView intent(long capturedAmount, long refundedAmount) {
    return new PaymentIntentView(
        INTENT_ID,
        "merchant-demo-01",
        "Northstar Goods",
        12500,
        "USD",
        "Synthetic order",
        PaymentStatus.CAPTURED,
        SimulationOutcome.APPROVE,
        capturedAmount,
        refundedAmount,
        0,
        null,
        "2026-10-30T12:00:00Z",
        "2026-10-30T12:00:00Z",
        "2026-10-30T12:01:00Z",
        "2026-10-30T12:05:00Z");
  }
}
