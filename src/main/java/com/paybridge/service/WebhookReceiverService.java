package com.paybridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.api.ApiDtos.PaymentIntentView;
import com.paybridge.api.ApiDtos.ProviderWebhookPayload;
import com.paybridge.api.ApiDtos.WebhookAck;
import com.paybridge.api.ApiException;
import com.paybridge.persistence.PaymentRepository;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookReceiverService {
  private static final Logger LOGGER = LoggerFactory.getLogger(WebhookReceiverService.class);
  private static final Pattern SAFE_EVENT_ID = Pattern.compile("[A-Za-z0-9._:-]{1,80}");
  private static final Duration MAX_EVENT_AGE = Duration.ofMinutes(5);

  private final ObjectMapper objectMapper;
  private final WebhookSignatureService signatureService;
  private final PaymentRepository paymentRepository;

  public WebhookReceiverService(
      ObjectMapper objectMapper,
      WebhookSignatureService signatureService,
      PaymentRepository paymentRepository) {
    this.objectMapper = objectMapper;
    this.signatureService = signatureService;
    this.paymentRepository = paymentRepository;
  }

  @Transactional
  public WebhookAck receive(byte[] rawPayload, String signature) {
    if (rawPayload.length == 0 || rawPayload.length > 16_384) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK", "Webhook payload is invalid");
    }
    if (!signatureService.verify(rawPayload, signature)) {
      throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE", "Webhook signature is invalid");
    }

    ProviderWebhookPayload payload = parsePayload(rawPayload);
    validatePayload(payload);
    String payloadHash = Hashing.sha256(rawPayload);
    Optional<String> previousHash = paymentRepository.findWebhookHash(payload.eventId());
    if (previousHash.isPresent()) {
      if (!previousHash.get().equals(payloadHash)) {
        throw new ApiException(HttpStatus.CONFLICT, "WEBHOOK_EVENT_CONFLICT", "Event ID was reused with new content");
      }
      return new WebhookAck(payload.eventId(), true, true, "DUPLICATE");
    }

    PaymentIntentView intent = paymentRepository.findPayment(payload.intentId(), null)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment was not found"));
    validateAmount(payload, intent);
    paymentRepository.insertWebhook(payload, payloadHash);
    paymentRepository.markOutboxDelivered(payload.eventId());
    LOGGER.info("eventName=webhook.signature.verified outcome=success");
    return new WebhookAck(payload.eventId(), false, true, "DELIVERED");
  }

  private ProviderWebhookPayload parsePayload(byte[] rawPayload) {
    try {
      return objectMapper.readValue(rawPayload, ProviderWebhookPayload.class);
    } catch (IOException exception) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK", "Webhook payload is invalid");
    }
  }

  private void validatePayload(ProviderWebhookPayload payload) {
    if (payload.eventId() == null || !SAFE_EVENT_ID.matcher(payload.eventId()).matches()
        || payload.intentId() == null || payload.intentId().isBlank()
        || payload.amountMinor() <= 0 || payload.occurredAt() == null || payload.eventType() == null
        || !payload.eventType().equals("PAYMENT_CAPTURED") && !payload.eventType().equals("PAYMENT_REFUNDED")) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK", "Webhook payload is invalid");
    }
    Duration age = Duration.between(payload.occurredAt(), Instant.now()).abs();
    if (age.compareTo(MAX_EVENT_AGE) > 0) {
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "STALE_WEBHOOK", "Webhook timestamp is outside the accepted window");
    }
  }

  private void validateAmount(ProviderWebhookPayload payload, PaymentIntentView intent) {
    long maximumAmount = payload.eventType().equals("PAYMENT_CAPTURED")
        ? intent.capturedAmountMinor()
        : intent.refundedAmountMinor();
    if (payload.amountMinor() > maximumAmount) {
      throw new ApiException(
          HttpStatus.CONFLICT, "WEBHOOK_AMOUNT_MISMATCH", "Webhook amount does not match the payment");
    }
  }
}