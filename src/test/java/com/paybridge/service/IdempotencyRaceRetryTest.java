package com.paybridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.paybridge.api.ApiException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

class IdempotencyRaceRetryTest {
  private final IdempotencyRaceRetry retry = new IdempotencyRaceRetry();
  private final AtomicInteger calls = new AtomicInteger();

  @Test
  void returnsTheFirstResultWithoutRunningTheCommandAgain() {
    assertEquals("first", retry.run(() -> {
      calls.incrementAndGet();
      return "first";
    }));
    assertEquals(1, calls.get());
  }

  @Test
  void replaysOnceAfterAUniqueKeyRace() {
    assertEquals("replayed", retry.run(failingOnceWith(new DuplicateKeyException("uq_action_idempotency"))));
    assertEquals(2, calls.get());
  }

  @Test
  void replaysOnceAfterAGuardedUpdateLostTheRace() {
    assertEquals("replayed", retry.run(failingOnceWith(conflict("PAYMENT_STATE_CONFLICT"))));
    assertEquals(2, calls.get());
  }

  @Test
  void replaysOnceAfterATransitionCheckSawTheWinnersState() {
    assertEquals("replayed", retry.run(failingOnceWith(conflict("INVALID_PAYMENT_TRANSITION"))));
    assertEquals(2, calls.get());
  }

  @Test
  void neverRetriesBusinessRejectionsOrOtherFailures() {
    ApiException exceedsCapture = conflict("REFUND_EXCEEDS_CAPTURE");
    assertSame(exceedsCapture, assertThrows(ApiException.class, () -> retry.run(alwaysFailingWith(exceedsCapture))));
    ApiException forbidden = new ApiException(HttpStatus.FORBIDDEN, "ROLE_REQUIRED", "Elevated role required");
    assertSame(forbidden, assertThrows(ApiException.class, () -> retry.run(alwaysFailingWith(forbidden))));
    ApiException nonConflict = new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_STATE_CONFLICT", "Wrong status");
    assertSame(nonConflict, assertThrows(ApiException.class, () -> retry.run(alwaysFailingWith(nonConflict))));
    IllegalStateException unexpected = new IllegalStateException("boom");
    assertSame(unexpected, assertThrows(IllegalStateException.class, () -> retry.run(alwaysFailingWith(unexpected))));
    assertEquals(4, calls.get());
  }

  @Test
  void stopsAfterOneRetryAndReturnsTheSecondFailureUnchanged() {
    DuplicateKeyException second = new DuplicateKeyException("still colliding");
    Supplier<String> command = () -> {
      if (calls.incrementAndGet() == 1) {
        throw new DuplicateKeyException("first collision");
      }
      throw second;
    };

    assertSame(second, assertThrows(DuplicateKeyException.class, () -> retry.run(command)));
    assertEquals(2, calls.get());
  }

  private Supplier<String> failingOnceWith(RuntimeException failure) {
    return () -> {
      if (calls.incrementAndGet() == 1) {
        throw failure;
      }
      return "replayed";
    };
  }

  private Supplier<String> alwaysFailingWith(RuntimeException failure) {
    return () -> {
      calls.incrementAndGet();
      throw failure;
    };
  }

  private static ApiException conflict(String code) {
    return new ApiException(HttpStatus.CONFLICT, code, "Conflict");
  }
}
