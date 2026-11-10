package com.paybridge.service;

import com.paybridge.api.ApiDtos.WorkspaceProfileView;
import com.paybridge.api.ApiException;
import com.paybridge.domain.AppRole;
import com.paybridge.domain.DemoPrincipal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceProfileCatalog {
  private static final Map<String, DemoPrincipal> PROFILES = Map.of(
      "merchant-northstar", new DemoPrincipal(
          "user-merchant-01", "Alex Morgan", AppRole.MERCHANT, "merchant-demo-01"),
      "merchant-cedar", new DemoPrincipal(
          "user-merchant-02", "Casey Rivera", AppRole.MERCHANT, "merchant-demo-02"),
      "operator", new DemoPrincipal("user-operator-01", "Morgan Patel", AppRole.OPERATOR, null),
      "auditor", new DemoPrincipal("user-auditor-01", "Sam Rivera", AppRole.AUDITOR, null));

  private final boolean localProfileLoginEnabled;

  public WorkspaceProfileCatalog(
      @Value("${paybridge.local-profile-login-enabled}") boolean localProfileLoginEnabled) {
    this.localProfileLoginEnabled = localProfileLoginEnabled;
  }

  public List<WorkspaceProfileView> listProfiles() {
    requireLocalProfileLoginEnabled();
    return PROFILES.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> new WorkspaceProfileView(
            entry.getKey(), entry.getValue().displayName(), entry.getValue().role()))
        .toList();
  }

  public DemoPrincipal authenticate(String accountId) {
    requireLocalProfileLoginEnabled();
    DemoPrincipal principal = PROFILES.get(accountId);
    if (principal == null) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROFILE", "Select a supported workspace profile");
    }
    return principal;
  }

  private void requireLocalProfileLoginEnabled() {
    if (!localProfileLoginEnabled) {
      throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Route not found");
    }
  }
}
