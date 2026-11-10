package com.paybridge.domain;

public enum PaymentStatus {
  CREATED,
  AUTHORIZED,
  CAPTURED,
  PARTIALLY_REFUNDED,
  REFUNDED,
  VOIDED,
  DECLINED
}