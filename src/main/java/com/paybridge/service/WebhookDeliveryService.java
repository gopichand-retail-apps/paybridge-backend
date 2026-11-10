package com.paybridge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.api.ApiDtos.ProviderWebhookPayload;
import com.paybridge.api.ApiDtos.WebhookAck;
import com.paybridge.api.ApiException;
import com.paybridge.persistence.PaymentRepository;
import com.paybridge.persistence.PaymentRepository.PendingWebhook;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WebhookDeliveryService {
  private final ObjectMapper objectMapper;
  private final WebhookSignatureService signatureService;
  private final WebhookReceiverService receiverService;
  private final PaymentRepository paymentRepository;

  public WebhookDeliveryService(
      ObjectMapper objectMapper,
      WebhookSignatureService signatureService,
      WebhookReceiverService receiverService,
      PaymentRepository paymentRepository) {
    this.objectMapper = objectMapper;
    this.signatureService = signatureService;
    this.receiverService = receiverService;
    this.paymentRepository = paymentRepository;
  }

  public List<WebhookAck> deliverPending(String intentId) {
    List<PendingWebhook> pending = paymentRepository.findPendingWebhooks(intentId);
    return pending.stream().map(this::deliver).toList();
  }

  private WebhookAck deliver(PendingWebhook event) {
    ProviderWebhookPayload payload = new ProviderWebhookPayload(
        event.eventId(), event.eventType(), event.intentId(), event.amountMinor(), Instant.now());
    byte[] rawPayload = serialize(payload);
    return receiverService.receive(rawPayload, signatureService.sign(rawPayload));
  }

  private byte[] serialize(ProviderWebhookPayload payload) {
    try {
      return objectMapper.writeValueAsBytes(payload);
    } catch (JsonProcessingException exception) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "WEBHOOK_SERIALIZATION_FAILED",
          "The simulated webhook could not be created");
    }
  }
}