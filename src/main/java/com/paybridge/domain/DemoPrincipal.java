package com.paybridge.domain;

public record DemoPrincipal(String id, String displayName, AppRole role, String merchantId) {
  public boolean isMerchant() {
    return role == AppRole.MERCHANT;
  }
}