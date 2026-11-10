package com.paybridge.service;

import com.paybridge.domain.SimulationOutcome;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PaymentProviderSimulator {
  public AuthorizationResult authorize(SimulationOutcome outcome) {
    String reference = reference("AUTH");
    return new AuthorizationResult(outcome == SimulationOutcome.APPROVE, reference,
        outcome == SimulationOutcome.APPROVE ? null : "SIMULATED_DECLINE");
  }

  public String captureReference() {
    return reference("CAP");
  }

  private String reference(String operation) {
    return "SIM-" + operation + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  public record AuthorizationResult(boolean approved, String providerReference, String declineCode) {}
}