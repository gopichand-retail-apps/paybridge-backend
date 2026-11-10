package com.paybridge.api;

import com.paybridge.api.ApiDtos.AuditEventsResponse;
import com.paybridge.api.ApiDtos.OverviewView;
import com.paybridge.api.ApiDtos.ReconciliationsResponse;
import com.paybridge.domain.DemoPrincipal;
import com.paybridge.service.PaymentApplicationService;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class OperationsController {
  private final PaymentApplicationService paymentService;
  private final BuildProperties buildProperties;

  public OperationsController(PaymentApplicationService paymentService, BuildProperties buildProperties) {
    this.paymentService = paymentService;
    this.buildProperties = buildProperties;
  }

  @GetMapping("/version")
  public Map<String, String> version(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return Map.of("service", "paybridge-api", "version", buildProperties.getVersion());
  }

  @GetMapping("/overview")
  public OverviewView overview(@AuthenticationPrincipal DemoPrincipal principal) {
    return paymentService.overview(principal);
  }

  @GetMapping("/reconciliations")
  public ReconciliationsResponse reconciliations(
      @AuthenticationPrincipal DemoPrincipal principal,
      @RequestParam(defaultValue = "50") int limit) {
    return new ReconciliationsResponse(paymentService.reconciliations(principal, limit));
  }

  @GetMapping("/audit-events")
  public AuditEventsResponse auditEvents(
      @AuthenticationPrincipal DemoPrincipal principal,
      @RequestParam(defaultValue = "50") int limit) {
    return new AuditEventsResponse(paymentService.auditEvents(principal, limit));
  }
}