package com.paybridge.persistence;

import com.paybridge.api.ApiDtos.AuditEventView;
import com.paybridge.api.ApiDtos.OverviewView;
import com.paybridge.api.ApiDtos.PaymentIntentView;
import com.paybridge.api.ApiDtos.ReconciliationView;
import com.paybridge.api.ApiDtos.WebhookEventView;
import com.paybridge.api.ApiDtos.ProviderWebhookPayload;
import com.paybridge.api.ApiException;
import com.paybridge.domain.PaymentStatus;
import com.paybridge.domain.SimulationOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {
  private static final String PAYMENT_SELECT = """
      SELECT pi.id, pi.merchant_id, m.display_name AS merchant_name, pi.amount_minor, pi.currency,
        pi.description, pi.status, pi.simulation_outcome, pi.captured_amount_minor,
        pi.refunded_amount_minor,
        (SELECT COUNT(*) FROM webhook_events we WHERE we.payment_intent_id = pi.id) AS webhook_count,
        (SELECT r.result FROM reconciliations r WHERE r.payment_intent_id = pi.id
          ORDER BY r.reconciled_at DESC LIMIT 1) AS latest_reconciliation,
        pi.created_at, pi.updated_at, pi.authorized_at, pi.captured_at
      FROM payment_intents pi
      JOIN merchants m ON m.id = pi.merchant_id
      """;

  private static final RowMapper<PaymentIntentView> PAYMENT_MAPPER = (resultSet, rowNumber) ->
      new PaymentIntentView(
          resultSet.getString("id"),
          resultSet.getString("merchant_id"),
          resultSet.getString("merchant_name"),
          resultSet.getLong("amount_minor"),
          resultSet.getString("currency"),
          resultSet.getString("description"),
          PaymentStatus.valueOf(resultSet.getString("status")),
          SimulationOutcome.valueOf(resultSet.getString("simulation_outcome")),
          resultSet.getLong("captured_amount_minor"),
          resultSet.getLong("refunded_amount_minor"),
          resultSet.getInt("webhook_count"),
          resultSet.getString("latest_reconciliation"),
          timestamp(resultSet, "created_at"),
          timestamp(resultSet, "updated_at"),
          timestamp(resultSet, "authorized_at"),
          timestamp(resultSet, "captured_at"));

  private final JdbcTemplate jdbcTemplate;

  public PaymentRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<PaymentIntentView> findPayments(String merchantId, String status, String search, int limit) {
    StringBuilder sql = new StringBuilder(PAYMENT_SELECT).append(" WHERE 1 = 1");
    List<Object> parameters = new ArrayList<>();
    appendMerchantScope(sql, parameters, merchantId);
    if (status != null) {
      sql.append(" AND pi.status = ?");
      parameters.add(status);
    }
    if (search != null && !search.isBlank()) {
      sql.append(" AND (LOWER(pi.description) LIKE ? OR LOWER(pi.id) LIKE ?)");
      String searchTerm = "%" + search.toLowerCase() + "%";
      parameters.add(searchTerm);
      parameters.add(searchTerm);
    }
    sql.append(" ORDER BY pi.created_at DESC LIMIT ?");
    parameters.add(limit);
    return jdbcTemplate.query(sql.toString(), PAYMENT_MAPPER, parameters.toArray());
  }

  public Optional<PaymentIntentView> findPayment(String intentId, String merchantId) {
    StringBuilder sql = new StringBuilder(PAYMENT_SELECT).append(" WHERE pi.id = ?");
    List<Object> parameters = new ArrayList<>(List.of(intentId));
    appendMerchantScope(sql, parameters, merchantId);
    return jdbcTemplate.query(sql.toString(), PAYMENT_MAPPER, parameters.toArray()).stream().findFirst();
  }

  public Optional<CreateIdempotencyRecord> findCreateIdempotency(String merchantId, String key) {
    return jdbcTemplate.query(
        "SELECT id, request_hash FROM payment_intents WHERE merchant_id = ? AND idempotency_key = ?",
        (resultSet, rowNumber) -> new CreateIdempotencyRecord(
            resultSet.getString("id"), resultSet.getString("request_hash")),
        merchantId,
        key).stream().findFirst();
  }

  public void insertIntent(
      String id,
      String merchantId,
      long amountMinor,
      String currency,
      String description,
      String simulationOutcome,
      String idempotencyKey,
      String requestHash) {
    jdbcTemplate.update("""
        INSERT INTO payment_intents (
          id, merchant_id, amount_minor, currency, description, status, simulation_outcome,
          idempotency_key, request_hash
        ) VALUES (?, ?, ?, ?, ?, 'CREATED', ?, ?, ?)
        """, id, merchantId, amountMinor, currency, description, simulationOutcome, idempotencyKey, requestHash);
  }

  public Optional<ActionIdempotencyRecord> findActionIdempotency(
      String actorId,
      String operation,
      String idempotencyKey) {
    return jdbcTemplate.query("""
        SELECT request_hash, entity_id FROM idempotency_records
        WHERE actor_id = ? AND operation = ? AND idempotency_key = ?
        """, (resultSet, rowNumber) -> new ActionIdempotencyRecord(
        resultSet.getString("request_hash"), resultSet.getString("entity_id")),
        actorId,
        operation,
        idempotencyKey).stream().findFirst();
  }

  public void insertActionIdempotency(
      String actorId,
      String operation,
      String idempotencyKey,
      String requestHash,
      String entityId) {
    jdbcTemplate.update("""
        INSERT INTO idempotency_records (id, actor_id, operation, idempotency_key, request_hash, entity_id)
        VALUES (?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID().toString(), actorId, operation, idempotencyKey, requestHash, entityId);
  }

  public int countAttempts(String intentId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_attempts WHERE payment_intent_id = ?", Integer.class, intentId);
  }

  public void insertAttempt(
      String intentId,
      int attemptNumber,
      String outcome,
      String providerReference,
      String declineCode,
      String idempotencyKey) {
    jdbcTemplate.update("""
        INSERT INTO payment_attempts (
          id, payment_intent_id, attempt_number, outcome, provider_reference, decline_code, idempotency_key
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID().toString(), intentId, attemptNumber, outcome, providerReference,
        declineCode, idempotencyKey);
  }

  public void updateAuthorization(String intentId, String status) {
    int updated = jdbcTemplate.update("""
        UPDATE payment_intents
        SET status = ?, authorized_at = CASE WHEN ? = 'AUTHORIZED' THEN CURRENT_TIMESTAMP ELSE authorized_at END,
          updated_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE id = ? AND status = 'CREATED'
        """, status, status, intentId);
    requireSingleUpdate(updated);
  }

  public void insertCapture(String intentId, long amountMinor, String providerReference, String idempotencyKey) {
    jdbcTemplate.update("""
        INSERT INTO payment_captures (id, payment_intent_id, amount_minor, provider_reference, idempotency_key)
        VALUES (?, ?, ?, ?, ?)
        """, UUID.randomUUID().toString(), intentId, amountMinor, providerReference, idempotencyKey);
  }

  public void updateCapture(String intentId, long amountMinor) {
    int updated = jdbcTemplate.update("""
        UPDATE payment_intents
        SET status = 'CAPTURED', captured_amount_minor = ?, captured_at = CURRENT_TIMESTAMP,
          updated_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE id = ? AND status = 'AUTHORIZED' AND captured_amount_minor = 0
        """, amountMinor, intentId);
    requireSingleUpdate(updated);
  }

  public void updateVoid(String intentId) {
    int updated = jdbcTemplate.update("""
        UPDATE payment_intents
        SET status = 'VOIDED', updated_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE id = ? AND status = 'AUTHORIZED'
        """, intentId);
    requireSingleUpdate(updated);
  }

  public void insertRefund(String intentId, long amountMinor, String reason, String idempotencyKey) {
    jdbcTemplate.update("""
        INSERT INTO payment_refunds (id, payment_intent_id, amount_minor, reason, idempotency_key)
        VALUES (?, ?, ?, ?, ?)
        """, UUID.randomUUID().toString(), intentId, amountMinor, reason, idempotencyKey);
  }

  public boolean updateRefund(String intentId, long refundAmountMinor) {
    int updated = jdbcTemplate.update("""
        UPDATE payment_intents
        SET status = CASE
              WHEN refunded_amount_minor + ? = captured_amount_minor THEN 'REFUNDED'
              ELSE 'PARTIALLY_REFUNDED'
            END,
          refunded_amount_minor = refunded_amount_minor + ?,
          updated_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE id = ? AND status IN ('CAPTURED', 'PARTIALLY_REFUNDED')
          AND captured_amount_minor - refunded_amount_minor >= ?
        """, refundAmountMinor, refundAmountMinor, intentId, refundAmountMinor);
    return updated == 1;
  }

  public void insertOutboxEvent(String intentId, String eventType, long amountMinor) {
    jdbcTemplate.update("""
        INSERT INTO webhook_outbox (event_id, payment_intent_id, event_type, amount_minor, delivery_status)
        VALUES (?, ?, ?, ?, 'PENDING')
        """, UUID.randomUUID().toString(), intentId, eventType, amountMinor);
  }

  public List<PendingWebhook> findPendingWebhooks(String intentId) {
    return jdbcTemplate.query("""
        SELECT event_id, payment_intent_id, event_type, amount_minor
        FROM webhook_outbox WHERE payment_intent_id = ? AND delivery_status = 'PENDING'
        ORDER BY created_at ASC
        """, (resultSet, rowNumber) -> new PendingWebhook(
        resultSet.getString("event_id"),
        resultSet.getString("payment_intent_id"),
        resultSet.getString("event_type"),
        resultSet.getLong("amount_minor")), intentId);
  }

  public Optional<String> findWebhookHash(String eventId) {
    return jdbcTemplate.query(
        "SELECT payload_hash FROM webhook_events WHERE event_id = ?",
        (resultSet, rowNumber) -> resultSet.getString("payload_hash"), eventId).stream().findFirst();
  }

  public void insertWebhook(ProviderWebhookPayload payload, String payloadHash) {
    jdbcTemplate.update("""
        INSERT INTO webhook_events (
          event_id, payment_intent_id, event_type, amount_minor, payload_hash, signature_verified
        ) VALUES (?, ?, ?, ?, ?, TRUE)
        """, payload.eventId(), payload.intentId(), payload.eventType(), payload.amountMinor(), payloadHash);
  }

  public void markOutboxDelivered(String eventId) {
    jdbcTemplate.update("""
        UPDATE webhook_outbox
        SET delivery_status = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP
        WHERE event_id = ? AND delivery_status = 'PENDING'
        """, eventId);
  }

  public long sumWebhookAmount(String intentId, String eventType) {
    Long total = jdbcTemplate.queryForObject("""
        SELECT COALESCE(SUM(amount_minor), 0) FROM webhook_events
        WHERE payment_intent_id = ? AND event_type = ?
        """, Long.class, intentId, eventType);
    return total == null ? 0 : total;
  }

  public long countPendingWebhooks(String intentId) {
    Long count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM webhook_outbox
        WHERE payment_intent_id = ? AND delivery_status = 'PENDING'
        """, Long.class, intentId);
    return count == null ? 0 : count;
  }

  public List<WebhookEventView> findWebhookEvents(String merchantId, String intentId, int limit) {
    StringBuilder sql = new StringBuilder("""
        SELECT we.event_id, we.payment_intent_id, we.event_type, we.amount_minor,
          we.signature_verified, we.received_at
        FROM webhook_events we JOIN payment_intents pi ON pi.id = we.payment_intent_id
        WHERE 1 = 1
        """);
    List<Object> parameters = new ArrayList<>();
    if (merchantId != null) {
      sql.append(" AND pi.merchant_id = ?");
      parameters.add(merchantId);
    }
    if (intentId != null) {
      sql.append(" AND we.payment_intent_id = ?");
      parameters.add(intentId);
    }
    sql.append(" ORDER BY we.received_at DESC LIMIT ?");
    parameters.add(limit);
    return jdbcTemplate.query(sql.toString(), (resultSet, rowNumber) -> new WebhookEventView(
        resultSet.getString("event_id"),
        resultSet.getString("payment_intent_id"),
        resultSet.getString("event_type"),
        resultSet.getLong("amount_minor"),
        resultSet.getBoolean("signature_verified"),
        timestamp(resultSet, "received_at")), parameters.toArray());
  }

  public String insertReconciliation(
      String intentId,
      long expectedCaptured,
      long observedCaptured,
      long expectedRefunded,
      long observedRefunded,
      String result) {
    String reconciliationId = UUID.randomUUID().toString();
    jdbcTemplate.update("""
        INSERT INTO reconciliations (
          id, payment_intent_id, expected_captured_minor, observed_captured_minor,
          expected_refunded_minor, observed_refunded_minor, result
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """, reconciliationId, intentId, expectedCaptured, observedCaptured,
        expectedRefunded, observedRefunded, result);
    return reconciliationId;
  }

  public Optional<ReconciliationView> findReconciliation(String reconciliationId) {
    return jdbcTemplate.query("""
        SELECT id, payment_intent_id, expected_captured_minor, observed_captured_minor,
          expected_refunded_minor, observed_refunded_minor, result, reconciled_at
        FROM reconciliations WHERE id = ?
        """, (resultSet, rowNumber) -> new ReconciliationView(
        resultSet.getString("id"),
        resultSet.getString("payment_intent_id"),
        resultSet.getLong("expected_captured_minor"),
        resultSet.getLong("observed_captured_minor"),
        resultSet.getLong("expected_refunded_minor"),
        resultSet.getLong("observed_refunded_minor"),
        resultSet.getString("result"),
        timestamp(resultSet, "reconciled_at")), reconciliationId).stream().findFirst();
  }

  public List<ReconciliationView> findReconciliations(String merchantId, int limit) {
    StringBuilder sql = new StringBuilder("""
        SELECT r.id, r.payment_intent_id, r.expected_captured_minor, r.observed_captured_minor,
          r.expected_refunded_minor, r.observed_refunded_minor, r.result, r.reconciled_at
        FROM reconciliations r JOIN payment_intents pi ON pi.id = r.payment_intent_id
        WHERE 1 = 1
        """);
    List<Object> parameters = new ArrayList<>();
    if (merchantId != null) {
      sql.append(" AND pi.merchant_id = ?");
      parameters.add(merchantId);
    }
    sql.append(" ORDER BY r.reconciled_at DESC LIMIT ?");
    parameters.add(limit);
    return jdbcTemplate.query(sql.toString(), (resultSet, rowNumber) -> new ReconciliationView(
        resultSet.getString("id"),
        resultSet.getString("payment_intent_id"),
        resultSet.getLong("expected_captured_minor"),
        resultSet.getLong("observed_captured_minor"),
        resultSet.getLong("expected_refunded_minor"),
        resultSet.getLong("observed_refunded_minor"),
        resultSet.getString("result"),
        timestamp(resultSet, "reconciled_at")), parameters.toArray());
  }

  public void insertAuditEvent(
      String actorId,
      String merchantId,
      String eventName,
      String entityType,
      String entityId) {
    jdbcTemplate.update("""
        INSERT INTO audit_events (id, actor_id, merchant_id, event_name, entity_type, entity_id)
        VALUES (?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID().toString(), actorId, merchantId, eventName, entityType, entityId);
  }

  public List<AuditEventView> findAuditEvents(String merchantId, int limit) {
    StringBuilder sql = new StringBuilder("""
        SELECT id, actor_id, merchant_id, event_name, entity_type, entity_id, created_at
        FROM audit_events WHERE 1 = 1
        """);
    List<Object> parameters = new ArrayList<>();
    if (merchantId != null) {
      sql.append(" AND merchant_id = ?");
      parameters.add(merchantId);
    }
    sql.append(" ORDER BY created_at DESC LIMIT ?");
    parameters.add(limit);
    return jdbcTemplate.query(sql.toString(), (resultSet, rowNumber) -> new AuditEventView(
        resultSet.getString("id"),
        resultSet.getString("actor_id"),
        resultSet.getString("merchant_id"),
        resultSet.getString("event_name"),
        resultSet.getString("entity_type"),
        resultSet.getString("entity_id"),
        timestamp(resultSet, "created_at")), parameters.toArray());
  }

  public OverviewView overview(String merchantId) {
    StringBuilder sql = new StringBuilder("""
        SELECT COUNT(*) AS total_count,
          COALESCE(SUM(captured_amount_minor), 0) AS captured_amount,
          COALESCE(SUM(refunded_amount_minor), 0) AS refunded_amount,
          SUM(CASE WHEN status = 'CREATED' THEN 1 ELSE 0 END) AS created_count,
          SUM(CASE WHEN status = 'DECLINED' THEN 1 ELSE 0 END) AS declined_count,
          SUM(CASE WHEN status = 'CAPTURED' OR status = 'PARTIALLY_REFUNDED' OR status = 'REFUNDED'
            THEN 1 ELSE 0 END) AS captured_count,
          SUM(CASE WHEN status = 'AUTHORIZED' THEN 1 ELSE 0 END) AS authorized_count
        FROM payment_intents pi WHERE 1 = 1
        """);
    List<Object> parameters = new ArrayList<>();
    appendMerchantScope(sql, parameters, merchantId);
    OverviewCounts counts = jdbcTemplate.queryForObject(sql.toString(), (resultSet, rowNumber) ->
        new OverviewCounts(
            resultSet.getLong("total_count"),
            resultSet.getLong("captured_amount"),
            resultSet.getLong("refunded_amount"),
            resultSet.getLong("created_count"),
            resultSet.getLong("declined_count"),
            resultSet.getLong("captured_count"),
            resultSet.getLong("authorized_count")), parameters.toArray());
    String merchantFilter = merchantId == null ? "" : " AND pi.merchant_id = ?";
    List<Object> eventParameters = new ArrayList<>();
    if (merchantId != null) eventParameters.add(merchantId);
    long pending = count("""
        SELECT COUNT(*) FROM webhook_outbox wo JOIN payment_intents pi ON pi.id = wo.payment_intent_id
        WHERE wo.delivery_status = 'PENDING'
        """ + merchantFilter, eventParameters.toArray());
    long mismatches = count("""
        SELECT COUNT(*) FROM (
          SELECT r.payment_intent_id, MAX(r.reconciled_at) AS latest_at
          FROM reconciliations r JOIN payment_intents pi ON pi.id = r.payment_intent_id
          WHERE r.result = 'MISMATCH'
        """ + merchantFilter + " GROUP BY r.payment_intent_id) latest", eventParameters.toArray());
    return new OverviewView(counts.totalCount(), counts.capturedAmount(), counts.refundedAmount(),
        counts.createdCount(), counts.declinedCount(), pending, mismatches,
        counts.capturedCount(), counts.authorizedCount());
  }

  private long count(String sql, Object... parameters) {
    Long result = jdbcTemplate.queryForObject(sql, Long.class, parameters);
    return result == null ? 0 : result;
  }

  private static void appendMerchantScope(StringBuilder sql, List<Object> parameters, String merchantId) {
    if (merchantId != null) {
      sql.append(" AND pi.merchant_id = ?");
      parameters.add(merchantId);
    }
  }

  private static void requireSingleUpdate(int updated) {
    if (updated != 1) {
      throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_STATE_CONFLICT",
          "Payment state changed; reload and retry");
    }
  }

  private static String timestamp(ResultSet resultSet, String column) throws SQLException {
    Object value = resultSet.getObject(column);
    if (value == null) return null;
    if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant().toString();
    if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
    return value.toString();
  }

  public record CreateIdempotencyRecord(String intentId, String requestHash) {}

  public record ActionIdempotencyRecord(String requestHash, String entityId) {}

  public record PendingWebhook(String eventId, String intentId, String eventType, long amountMinor) {}

  private record OverviewCounts(
      long totalCount,
      long capturedAmount,
      long refundedAmount,
      long createdCount,
      long declinedCount,
      long capturedCount,
      long authorizedCount) {}
}