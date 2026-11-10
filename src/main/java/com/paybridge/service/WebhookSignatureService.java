package com.paybridge.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebhookSignatureService {
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private final byte[] key;

  public WebhookSignatureService(
      @Value("${paybridge.webhook.secret:}") String configuredSecret,
      @Value("${paybridge.require-webhook-secret:false}") boolean requireConfiguredSecret) {
    if (configuredSecret.isBlank() && requireConfiguredSecret) {
      throw new IllegalStateException("PAYBRIDGE_WEBHOOK_SECRET must be configured for this profile");
    }
    if (configuredSecret.isBlank()) {
      key = new byte[32];
      new SecureRandom().nextBytes(key);
    } else {
      key = configuredSecret.getBytes(StandardCharsets.UTF_8);
      if (key.length < 32) {
        throw new IllegalStateException("PAYBRIDGE_WEBHOOK_SECRET must contain at least 32 bytes");
      }
    }
  }

  public String sign(byte[] payload) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
      return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    } catch (Exception exception) {
      throw new IllegalStateException("Webhook signing is unavailable", exception);
    }
  }

  public boolean verify(byte[] payload, String signature) {
    if (signature == null || !signature.startsWith("sha256=")) return false;
    try {
      byte[] provided = HexFormat.of().parseHex(signature.substring("sha256=".length()));
      String expectedHex = sign(payload).substring("sha256=".length());
      byte[] expected = HexFormat.of().parseHex(expectedHex);
      return MessageDigest.isEqual(expected, provided);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}