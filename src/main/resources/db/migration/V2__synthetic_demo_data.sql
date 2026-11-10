INSERT INTO merchants (id, display_name)
VALUES ('merchant-demo-01', 'Northstar Goods · Demo Merchant');

INSERT INTO merchants (id, display_name)
VALUES ('merchant-demo-02', 'Cedar & Coast · Demo Merchant');

INSERT INTO payment_intents (
  id, merchant_id, amount_minor, currency, description, status, simulation_outcome,
  idempotency_key, request_hash, captured_amount_minor, refunded_amount_minor,
  created_at, updated_at, authorized_at, captured_at
) VALUES (
  'intent-demo-created', 'merchant-demo-01', 12500, 'USD', 'Online order · #1048', 'CREATED', 'APPROVE',
  'seed-created-001', 'seed-created-hash', 0, 0,
  CURRENT_TIMESTAMP - INTERVAL '18' MINUTE, CURRENT_TIMESTAMP - INTERVAL '18' MINUTE, NULL, NULL
);

INSERT INTO payment_intents (
  id, merchant_id, amount_minor, currency, description, status, simulation_outcome,
  idempotency_key, request_hash, captured_amount_minor, refunded_amount_minor,
  created_at, updated_at, authorized_at, captured_at
) VALUES (
  'intent-demo-authorized', 'merchant-demo-01', 7250, 'USD', 'In-store order · #1047', 'AUTHORIZED', 'APPROVE',
  'seed-authorized-001', 'seed-authorized-hash', 0, 0,
  CURRENT_TIMESTAMP - INTERVAL '32' MINUTE, CURRENT_TIMESTAMP - INTERVAL '31' MINUTE,
  CURRENT_TIMESTAMP - INTERVAL '31' MINUTE, NULL
);

INSERT INTO payment_attempts (
  id, payment_intent_id, attempt_number, outcome, provider_reference, idempotency_key,
  created_at
) VALUES (
  'attempt-demo-authorized', 'intent-demo-authorized', 1, 'APPROVED', 'SIM-AUTH-7A31',
  'seed-authorize-001', CURRENT_TIMESTAMP - INTERVAL '31' MINUTE
);

INSERT INTO payment_intents (
  id, merchant_id, amount_minor, currency, description, status, simulation_outcome,
  idempotency_key, request_hash, captured_amount_minor, refunded_amount_minor,
  created_at, updated_at, authorized_at, captured_at
) VALUES (
  'intent-demo-captured', 'merchant-demo-01', 4820, 'USD', 'Subscription renewal · #1046', 'CAPTURED', 'APPROVE',
  'seed-captured-001', 'seed-captured-hash', 4820, 0,
  CURRENT_TIMESTAMP - INTERVAL '1' HOUR, CURRENT_TIMESTAMP - INTERVAL '55' MINUTE,
  CURRENT_TIMESTAMP - INTERVAL '58' MINUTE, CURRENT_TIMESTAMP - INTERVAL '55' MINUTE
);

INSERT INTO payment_attempts (
  id, payment_intent_id, attempt_number, outcome, provider_reference, idempotency_key,
  created_at
) VALUES (
  'attempt-demo-captured', 'intent-demo-captured', 1, 'APPROVED', 'SIM-AUTH-2D83',
  'seed-authorize-002', CURRENT_TIMESTAMP - INTERVAL '58' MINUTE
);

INSERT INTO payment_captures (
  id, payment_intent_id, amount_minor, provider_reference, idempotency_key, captured_at
) VALUES (
  'capture-demo-captured', 'intent-demo-captured', 4820, 'SIM-CAP-5B19', 'seed-capture-001',
  CURRENT_TIMESTAMP - INTERVAL '55' MINUTE
);

INSERT INTO webhook_outbox (
  event_id, payment_intent_id, event_type, amount_minor, delivery_status, created_at, delivered_at
) VALUES (
  'event-demo-captured', 'intent-demo-captured', 'PAYMENT_CAPTURED', 4820, 'DELIVERED',
  CURRENT_TIMESTAMP - INTERVAL '55' MINUTE, CURRENT_TIMESTAMP - INTERVAL '55' MINUTE
);

INSERT INTO webhook_events (
  event_id, payment_intent_id, event_type, amount_minor, payload_hash, signature_verified, received_at
) VALUES (
  'event-demo-captured', 'intent-demo-captured', 'PAYMENT_CAPTURED', 4820,
  'seed-webhook-hash', TRUE, CURRENT_TIMESTAMP - INTERVAL '55' MINUTE
);

INSERT INTO reconciliations (
  id, payment_intent_id, expected_captured_minor, observed_captured_minor,
  expected_refunded_minor, observed_refunded_minor, result, reconciled_at
) VALUES (
  'reconciliation-demo-captured', 'intent-demo-captured', 4820, 4820, 0, 0, 'MATCHED',
  CURRENT_TIMESTAMP - INTERVAL '54' MINUTE
);

INSERT INTO payment_intents (
  id, merchant_id, amount_minor, currency, description, status, simulation_outcome,
  idempotency_key, request_hash, captured_amount_minor, refunded_amount_minor,
  created_at, updated_at
) VALUES (
  'intent-demo-declined', 'merchant-demo-01', 9900, 'USD', 'One-time invoice · #1045', 'DECLINED', 'DECLINE',
  'seed-declined-001', 'seed-declined-hash', 0, 0,
  CURRENT_TIMESTAMP - INTERVAL '2' HOUR, CURRENT_TIMESTAMP - INTERVAL '2' HOUR
);

INSERT INTO payment_attempts (
  id, payment_intent_id, attempt_number, outcome, provider_reference, decline_code, idempotency_key,
  created_at
) VALUES (
  'attempt-demo-declined', 'intent-demo-declined', 1, 'DECLINED', 'SIM-AUTH-8C02',
  'SIMULATED_DECLINE', 'seed-authorize-003', CURRENT_TIMESTAMP - INTERVAL '2' HOUR
);

INSERT INTO audit_events (id, actor_id, merchant_id, event_name, entity_type, entity_id, safe_metadata, created_at)
VALUES ('audit-demo-captured', 'user-merchant-01', 'merchant-demo-01', 'payment.capture.completed',
  'payment_intent', 'intent-demo-captured', '{"source":"synthetic_seed"}', CURRENT_TIMESTAMP - INTERVAL '55' MINUTE);