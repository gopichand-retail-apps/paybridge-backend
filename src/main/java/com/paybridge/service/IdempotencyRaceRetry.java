package com.paybridge.service;

import com.paybridge.api.ApiException;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Re-runs an idempotent write once in a fresh transaction when it lost a race against a request carrying the same
 * Idempotency-Key. The first attempt has already rolled back completely, so the second attempt sees the winner's
 * committed record and replays its result instead of failing or repeating the effect.
 *
 * <p>Call this outside the {@code @Transactional} boundary. It retries only the two signatures a lost race
 * produces; any other failure, including a second loss, is returned to the caller unchanged.
 */
@Component
public class IdempotencyRaceRetry {
  /** Raised by the guarded updates when another request changed the row first ("reload and retry"). */
  private static final String STATE_CONFLICT = "PAYMENT_STATE_CONFLICT";
  private static final String INVALID_TRANSITION = "INVALID_PAYMENT_TRANSITION";

  public <T> T run(Supplier<T> command) {
    try {
      return command.get();
    } catch (RuntimeException failure) {
      if (!lostAnIdempotencyRace(failure)) {
        throw failure;
      }
      return command.get();
    }
  }

  private static boolean lostAnIdempotencyRace(RuntimeException failure) {
    if (failure instanceof DuplicateKeyException) {
      return true;
    }
    return failure instanceof ApiException api
        && api.status() == HttpStatus.CONFLICT
        && (STATE_CONFLICT.equals(api.code()) || INVALID_TRANSITION.equals(api.code()));
  }
}
