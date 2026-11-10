CREATE TABLE merchants (
  id VARCHAR(64) PRIMARY KEY,
  display_name VARCHAR(120) NOT NULL
);

CREATE TABLE payment_intents (
  id VARCHAR(36) PRIMARY KEY,
  merchant_id VARCHAR(64) NOT NULL REFERENCES merchants(id),
  amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
  currency VARCHAR(3) NOT NULL CHECK (currency = 'USD'),
  description VARCHAR(160) NOT NULL,
  status VARCHAR(32) NOT NULL CHECK (
    status IN ('CREATED', 'AUTHORIZED', 'CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'VOIDED', 'DECLINED')
  ),
  simulation_outcome VARCHAR(16) NOT NULL CHECK (simulation_outcome IN ('APPROVE', 'DECLINE')),
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  captured_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (captured_amount_minor >= 0),
  refunded_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (refunded_amount_minor >= 0),
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  authorized_at TIMESTAMP WITH TIME ZONE,
  captured_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT uq_payment_intent_idempotency UNIQUE (merchant_id, idempotency_key),
  CONSTRAINT ck_payment_intent_refund CHECK (refunded_amount_minor <= captured_amount_minor)
);

CREATE INDEX idx_payment_intents_merchant_created
  ON payment_intents (merchant_id, created_at DESC);
CREATE INDEX idx_payment_intents_status_created
  ON payment_intents (status, created_at DESC);

CREATE TABLE payment_attempts (
  id VARCHAR(36) PRIMARY KEY,
  payment_intent_id VARCHAR(36) NOT NULL REFERENCES payment_intents(id),
  attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
  outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('APPROVED', 'DECLINED')),
  provider_reference VARCHAR(80) NOT NULL,
  decline_code VARCHAR(48),
  idempotency_key VARCHAR(128) NOT NULL UNIQUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_payment_attempt_number UNIQUE (payment_intent_id, attempt_number)
);

CREATE TABLE payment_captures (
  id VARCHAR(36) PRIMARY KEY,
  payment_intent_id VARCHAR(36) NOT NULL REFERENCES payment_intents(id),
  amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
  provider_reference VARCHAR(80) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL UNIQUE,
  captured_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE payment_refunds (
  id VARCHAR(36) PRIMARY KEY,
  payment_intent_id VARCHAR(36) NOT NULL REFERENCES payment_intents(id),
  amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
  reason VARCHAR(160) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL UNIQUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE webhook_outbox (
  event_id VARCHAR(36) PRIMARY KEY,
  payment_intent_id VARCHAR(36) NOT NULL REFERENCES payment_intents(id),
  event_type VARCHAR(32) NOT NULL,
  amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
  delivery_status VARCHAR(16) NOT NULL CHECK (delivery_status IN ('PENDING', 'DELIVERED')),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  delivered_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_webhook_outbox_pending ON webhook_outbox (delivery_status, created_at);

CREATE TABLE webhook_events (
  event_id VARCHAR(36) PRIMARY KEY,
  payment_intent_id VARCHAR(36) NOT NULL REFERENCES payment_intents(id),
  event_type VARCHAR(32) NOT NULL,
  amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
  payload_hash VARCHAR(64) NOT NULL,
  signature_verified BOOLEAN NOT NULL,
  received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_webhook_events_intent ON webhook_events (payment_intent_id, received_at);

CREATE TABLE reconciliations (
  id VARCHAR(36) PRIMARY KEY,
  payment_intent_id VARCHAR(36) NOT NULL REFERENCES payment_intents(id),
  expected_captured_minor BIGINT NOT NULL,
  observed_captured_minor BIGINT NOT NULL,
  expected_refunded_minor BIGINT NOT NULL,
  observed_refunded_minor BIGINT NOT NULL,
  result VARCHAR(16) NOT NULL CHECK (result IN ('MATCHED', 'MISMATCH', 'PENDING')),
  reconciled_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE idempotency_records (
  id VARCHAR(36) PRIMARY KEY,
  actor_id VARCHAR(64) NOT NULL,
  operation VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  entity_id VARCHAR(36) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_action_idempotency UNIQUE (actor_id, operation, idempotency_key)
);

CREATE TABLE audit_events (
  id VARCHAR(36) PRIMARY KEY,
  actor_id VARCHAR(64) NOT NULL,
  merchant_id VARCHAR(64),
  event_name VARCHAR(80) NOT NULL,
  entity_type VARCHAR(40) NOT NULL,
  entity_id VARCHAR(64) NOT NULL,
  safe_metadata VARCHAR(500) NOT NULL DEFAULT '{}',
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_events_created ON audit_events (created_at DESC);