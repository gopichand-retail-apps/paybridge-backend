package com.paybridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.paybridge.api.ApiException;
import com.paybridge.domain.AppRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WorkspaceProfileCatalogTest {
  @Test
  void listsOnlySortedSyntheticProfilesAndAuthenticatesKnownAccounts() {
    WorkspaceProfileCatalog catalog = new WorkspaceProfileCatalog(true);

    var profiles = catalog.listProfiles();

    assertEquals(4, profiles.size());
    assertEquals(
        java.util.List.of("auditor", "merchant-cedar", "merchant-northstar", "operator"),
        profiles.stream().map(profile -> profile.id()).toList());
    assertEquals(AppRole.MERCHANT, catalog.authenticate("merchant-northstar").role());
    assertEquals("merchant-demo-02", catalog.authenticate("merchant-cedar").merchantId());
    assertEquals(AppRole.OPERATOR, catalog.authenticate("operator").role());
    assertEquals(AppRole.AUDITOR, catalog.authenticate("auditor").role());
  }

  @Test
  void rejectsUnknownProfilesAndDisablesLocalProfileRoutesWhenConfigured() {
    WorkspaceProfileCatalog enabled = new WorkspaceProfileCatalog(true);
    ApiException unknown = assertThrows(ApiException.class, () -> enabled.authenticate("unknown"));
    assertEquals("INVALID_PROFILE", unknown.code());
    assertEquals(HttpStatus.BAD_REQUEST, unknown.status());

    WorkspaceProfileCatalog disabled = new WorkspaceProfileCatalog(false);
    assertEquals("NOT_FOUND",
        assertThrows(ApiException.class, disabled::listProfiles).code());
    assertEquals("NOT_FOUND",
        assertThrows(ApiException.class, () -> disabled.authenticate("merchant-northstar")).code());
  }

  @Test
  void limitsAttemptsPerAddressAndStartsANewWindowAfterTheConfiguredInterval() {
    MutableClock clock = new MutableClock(Instant.parse("2026-10-30T12:00:00Z"));
    DemoLoginRateLimiter limiter = new DemoLoginRateLimiter(clock);

    for (int attempt = 0; attempt < DemoLoginRateLimiter.MAX_ATTEMPTS; attempt++) {
      limiter.requireAllowed("synthetic-client");
    }
    ApiException limited = assertThrows(ApiException.class, () -> limiter.requireAllowed("synthetic-client"));
    assertEquals("RATE_LIMITED", limited.code());
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, limited.status());

    clock.advance(Duration.ofSeconds(DemoLoginRateLimiter.WINDOW_SECONDS));
    limiter.requireAllowed("synthetic-client");
  }

  @Test
  void prunesExpiredAddressWindowsWhenTheBoundedCacheIsFull() throws ReflectiveOperationException {
    MutableClock clock = new MutableClock(Instant.parse("2026-10-30T12:00:00Z"));
    DemoLoginRateLimiter limiter = new DemoLoginRateLimiter(clock);

    for (int address = 0; address < DemoLoginRateLimiter.MAX_TRACKED_ADDRESSES; address++) {
      limiter.requireAllowed("synthetic-client-" + address);
    }
    clock.advance(Duration.ofSeconds(DemoLoginRateLimiter.WINDOW_SECONDS + 1));

    assertDoesNotThrow(() -> limiter.requireAllowed("fresh-synthetic-client"));
    Field windowsField = DemoLoginRateLimiter.class.getDeclaredField("windows");
    windowsField.setAccessible(true);
    Map<?, ?> windows = (Map<?, ?>) windowsField.get(limiter);
    assertEquals(1, windows.size());
  }

  private static final class MutableClock extends Clock {
    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }

    private void advance(Duration duration) {
      current = current.plus(duration);
    }
  }
}
