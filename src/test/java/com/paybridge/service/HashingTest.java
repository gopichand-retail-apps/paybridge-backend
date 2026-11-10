package com.paybridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class HashingTest {
  @Test
  void hashesUtf8InputWithSha256() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        Hashing.sha256("abc"));
  }

  @Test
  void reportsWhenSha256IsUnavailable() {
    try (MockedStatic<MessageDigest> provider = mockStatic(MessageDigest.class)) {
      provider.when(() -> MessageDigest.getInstance("SHA-256"))
          .thenThrow(new NoSuchAlgorithmException("SHA-256"));

      IllegalStateException exception =
          assertThrows(IllegalStateException.class, () -> Hashing.sha256(new byte[] { 1 }));

      assertEquals("SHA-256 is required by the Java runtime", exception.getMessage());
      assertInstanceOf(NoSuchAlgorithmException.class, exception.getCause());
    }
  }
}
