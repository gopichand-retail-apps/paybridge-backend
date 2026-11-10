package com.paybridge.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/client-errors")
public class ClientTelemetryController {
  private static final Logger LOGGER = LoggerFactory.getLogger(ClientTelemetryController.class);

  @PostMapping
  public Map<String, Boolean> recordClientError(@Valid @RequestBody ClientErrorRequest request) {
    LOGGER.warn("eventName={} outcome=observed", request.eventName());
    return Map.of("accepted", true);
  }

  public record ClientErrorRequest(
      @NotBlank @Pattern(regexp = "ui\\.error") String eventName) {}
}