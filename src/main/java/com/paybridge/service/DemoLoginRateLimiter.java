package com.paybridge.service;

import com.paybridge.api.ApiException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class DemoLoginRateLimiter {
  static final int MAX_ATTEMPTS = 20;
  static final long WINDOW_SECONDS = 60;
  static final int MAX_TRACKED_ADDRESSES = 10_000;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final Clock clock;

  public DemoLoginRateLimiter() {
    this(Clock.systemUTC());
  }

  DemoLoginRateLimiter(Clock clock) {
    this.clock = clock;
  }

  public void requireAllowed(String remoteAddress) {
    long now = clock.instant().getEpochSecond();
    Window window = windows.compute(remoteAddress, (key, current) -> {
      if (current == null || now - current.startedAt() >= WINDOW_SECONDS) {
        return new Window(now, 1);
      }
      return new Window(current.startedAt(), current.attempts() + 1);
    });
    if (windows.size() > MAX_TRACKED_ADDRESSES) {
      windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= WINDOW_SECONDS);
    }
    if (window.attempts() > MAX_ATTEMPTS) {
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Try again shortly");
    }
  }

  private record Window(long startedAt, int attempts) {}
}