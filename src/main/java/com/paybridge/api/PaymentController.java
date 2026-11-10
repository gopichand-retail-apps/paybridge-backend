package com.paybridge.api;

import com.paybridge.api.ApiDtos.CreateIntentRequest;
import com.paybridge.api.ApiDtos.PaymentIntentResponse;
import com.paybridge.api.ApiDtos.PaymentIntentsResponse;
import com.paybridge.api.ApiDtos.ReconciliationResponse;
import com.paybridge.api.ApiDtos.RefundRequest;
import com.paybridge.api.ApiDtos.WebhookDeliveryResponse;
import com.paybridge.domain.DemoPrincipal;
import com.paybridge.service.PaymentApplicationService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payment-intents")
public class PaymentController {
  private final PaymentApplicationService paymentService;

  public PaymentController(PaymentApplicationService paymentService) {
    this.paymentService = paymentService;
  }

  @GetMapping
  public PaymentIntentsResponse list(
      @AuthenticationPrincipal DemoPrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "50") int limit) {
    return new PaymentIntentsResponse(paymentService.listPayments(principal, status, q, limit));
  }

  @PostMapping
  public ResponseEntity<PaymentIntentResponse> create(
      @AuthenticationPrincipal DemoPrincipal principal,
      @Valid @RequestBody CreateIntentRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    PaymentApplicationService.CreateOutcome result = paymentService.createIntent(principal, request, idempotencyKey);
    HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
    return ResponseEntity.status(status).body(new PaymentIntentResponse(result.paymentIntent(), result.duplicate()));
  }

  @GetMapping("/{intentId}")
  public PaymentIntentResponse get(
      @PathVariable String intentId,
      @AuthenticationPrincipal DemoPrincipal principal) {
    return new PaymentIntentResponse(paymentService.getPayment(intentId, principal), false);
  }

  @PostMapping("/{intentId}/authorize")
  public PaymentIntentResponse authorize(
      @PathVariable String intentId,
      @AuthenticationPrincipal DemoPrincipal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return new PaymentIntentResponse(paymentService.authorize(intentId, key, principal), false);
  }

  @PostMapping("/{intentId}/capture")
  public PaymentIntentResponse capture(
      @PathVariable String intentId,
      @AuthenticationPrincipal DemoPrincipal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return new PaymentIntentResponse(paymentService.capture(intentId, key, principal), false);
  }

  @PostMapping("/{intentId}/void")
  public PaymentIntentResponse voidIntent(
      @PathVariable String intentId,
      @AuthenticationPrincipal DemoPrincipal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return new PaymentIntentResponse(paymentService.voidIntent(intentId, key, principal), false);
  }

  @PostMapping("/{intentId}/refunds")
  public PaymentIntentResponse refund(
      @PathVariable String intentId,
      @AuthenticationPrincipal DemoPrincipal principal,
      @Valid @RequestBody RefundRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return new PaymentIntentResponse(paymentService.refund(intentId, request, key, principal), false);
  }

  @PostMapping("/{intentId}/reconcile")
  public ReconciliationResponse reconcile(
      @PathVariable String intentId,
      @AuthenticationPrincipal DemoPrincipal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return new ReconciliationResponse(paymentService.reconcile(intentId, key, principal));
  }

  @PostMapping("/{intentId}/webhook-deliveries")
  public WebhookDeliveryResponse deliverWebhooks(
      @PathVariable String intentId,
      @AuthenticationPrincipal DemoPrincipal principal) {
    return new WebhookDeliveryResponse(paymentService.deliverWebhooks(intentId, principal));
  }
}