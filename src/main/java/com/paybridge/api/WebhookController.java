package com.paybridge.api;

import com.paybridge.api.ApiDtos.WebhookAck;
import com.paybridge.api.ApiDtos.WebhookEventsResponse;
import com.paybridge.domain.DemoPrincipal;
import com.paybridge.service.PaymentApplicationService;
import com.paybridge.service.WebhookReceiverService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WebhookController {
  private final PaymentApplicationService paymentService;
  private final WebhookReceiverService receiverService;

  public WebhookController(
      PaymentApplicationService paymentService,
      WebhookReceiverService receiverService) {
    this.paymentService = paymentService;
    this.receiverService = receiverService;
  }

  @GetMapping("/webhook-events")
  public WebhookEventsResponse webhookEvents(
      @AuthenticationPrincipal DemoPrincipal principal,
      @RequestParam(required = false) String intentId,
      @RequestParam(defaultValue = "50") int limit) {
    return new WebhookEventsResponse(paymentService.webhookEvents(principal, intentId, limit));
  }

  @PostMapping("/webhooks/receiver")
  public WebhookAck receiveProviderWebhook(
      @RequestBody byte[] rawBody,
      @RequestHeader(value = "X-PayBridge-Signature", required = false) String signature) {
    return receiverService.receive(rawBody, signature);
  }
}