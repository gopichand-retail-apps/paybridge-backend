package com.paybridge.domain;

public enum AppRole {
  MERCHANT,
  OPERATOR,
  AUDITOR;

  public String authority() {
    return "ROLE_" + name();
  }
}