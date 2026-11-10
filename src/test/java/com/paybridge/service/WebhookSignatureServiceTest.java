package com.paybridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;

import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class WebhookSignatureServiceTest {
  @Test
  void reportsWhenTheHmacProviderIsUnavailable() {
    WebhookSignatureService signer = new WebhookSignatureService("a".repeat(32), true);

    try (MockedStatic<Mac> provider = mockStatic(Mac.class)) {
      provider.when(() -> Mac.getInstance("HmacSHA256"))
          .thenThrow(new NoSuchAlgorithmException("HmacSHA256"));

      IllegalStateException exception =
          assertThrows(IllegalStateException.class, () -> signer.sign(new byte[] { 1 }));

      assertEquals("Webhook signing is unavailable", exception.getMessage());
      assertInstanceOf(NoSuchAlgorithmException.class, exception.getCause());
    }
  }
}
