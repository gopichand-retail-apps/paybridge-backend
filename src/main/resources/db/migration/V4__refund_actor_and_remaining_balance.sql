-- PB-02: a refund records who recorded it and the refundable balance left after it.
-- Expand step only: both columns are nullable so refunds written before this migration stay readable,
-- and older application code that does not set them keeps working during a rolling deploy.
ALTER TABLE payment_refunds ADD COLUMN actor_id VARCHAR(64);
ALTER TABLE payment_refunds ADD COLUMN remaining_after_minor BIGINT;
ALTER TABLE payment_refunds ADD CONSTRAINT ck_payment_refund_remaining
  CHECK (remaining_after_minor IS NULL OR remaining_after_minor >= 0);

CREATE INDEX idx_payment_refunds_intent ON payment_refunds (payment_intent_id, created_at);
