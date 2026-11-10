package com.paybridge.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentRepositoryTest {
  private JdbcTemplate jdbcTemplate;
  private PaymentRepository repository;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    repository = new PaymentRepository(jdbcTemplate);
  }

  @Test
  void treatsUnexpectedNullAggregateResultsAsZero() {
    when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(null);

    assertEquals(0L, repository.sumWebhookAmount("intent-1", "PAYMENT_CAPTURED"));
    assertEquals(0L, repository.countPendingWebhooks("intent-1"));

    Long count = ReflectionTestUtils.invokeMethod(
        repository, "count", "SELECT COUNT(*)", (Object) new Object[0]);
    assertEquals(0L, count);
  }

  @Test
  void mapsDatabaseTimestampRepresentationsAndNullValues() throws Exception {
    ResultSet resultSet = mock(ResultSet.class);
    OffsetDateTime offsetDateTime = OffsetDateTime.parse("2026-11-01T10:00:00+02:00");
    Timestamp timestamp = Timestamp.from(Instant.parse("2026-11-01T08:00:00Z"));
    List<Object> values = Arrays.asList(null, offsetDateTime, timestamp, "database-time");
    AtomicInteger index = new AtomicInteger();
    when(resultSet.getObject("value")).thenAnswer(invocation -> values.get(index.getAndIncrement()));

    assertNull(ReflectionTestUtils.invokeMethod(repository, "timestamp", resultSet, "value"));
    assertEquals(
        offsetDateTime.toInstant().toString(),
        ReflectionTestUtils.invokeMethod(repository, "timestamp", resultSet, "value"));
    assertEquals(
        timestamp.toInstant().toString(),
        ReflectionTestUtils.invokeMethod(repository, "timestamp", resultSet, "value"));
    assertEquals(
        "database-time",
        ReflectionTestUtils.invokeMethod(repository, "timestamp", resultSet, "value"));
  }
}
