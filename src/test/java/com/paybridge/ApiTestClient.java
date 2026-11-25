package com.paybridge;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Thin helper that drives the real controllers through MockMvc, including overlapping requests. */
final class ApiTestClient {
  private static final int WAIT_SECONDS = 20;
  private static final Map<String, MockHttpSession> SESSIONS = new ConcurrentHashMap<>();

  private final MockMvc mockMvc;
  private final ObjectMapper objectMapper;

  ApiTestClient(MockMvc mockMvc, ObjectMapper objectMapper) {
    this.mockMvc = mockMvc;
    this.objectMapper = objectMapper;
  }

  /**
   * One real login per profile for the whole test JVM. The demo login limiter (20 per minute per address) is a
   * product control that stays on, so tests reuse the resulting session instead of signing in every time.
   */
  MockHttpSession login(String accountId) throws Exception {
    MockHttpSession cached = SESSIONS.get(accountId);
    if (cached != null) {
      return cached;
    }
    MvcResult result = mockMvc.perform(post("/api/v1/auth/profile-login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(Map.of("accountId", accountId))))
        .andReturn();
    if (result.getResponse().getStatus() != 200) {
      throw new IllegalStateException("Sign-in as " + accountId + " returned " + result.getResponse().getStatus());
    }
    MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
    SESSIONS.put(accountId, session);
    return session;
  }

  String key(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request).andReturn().getResponse();
  }

  JsonNode json(MockHttpServletResponse response) throws Exception {
    return objectMapper.readTree(response.getContentAsByteArray());
  }

  MockHttpServletRequestBuilder createRequest(
      MockHttpSession session, String key, long amountMinor, String description) throws Exception {
    return post("/api/v1/payment-intents")
        .session(session)
        .with(csrf())
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsBytes(Map.of(
            "amountMinor", amountMinor,
            "currency", "USD",
            "description", description,
            "simulationOutcome", "APPROVE")));
  }

  MockHttpServletRequestBuilder actionRequest(
      MockHttpSession session, String intentId, String action, String key) {
    return post("/api/v1/payment-intents/{id}/{action}", intentId, action)
        .session(session)
        .with(csrf())
        .header("Idempotency-Key", key);
  }

  MockHttpServletRequestBuilder refundRequest(
      MockHttpSession session, String intentId, String key, long amountMinor, String reason) throws Exception {
    return post("/api/v1/payment-intents/{id}/refunds", intentId)
        .session(session)
        .with(csrf())
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsBytes(Map.of("amountMinor", amountMinor, "reason", reason)));
  }

  MockHttpServletRequestBuilder refundHistoryRequest(MockHttpSession session, String intentId) {
    return get("/api/v1/payment-intents/{id}/refunds", intentId).session(session);
  }

  MockHttpServletRequestBuilder anonymousRefundHistoryRequest(String intentId) {
    return get("/api/v1/payment-intents/{id}/refunds", intentId);
  }

  String createIntent(MockHttpSession merchant, long amountMinor) throws Exception {
    MockHttpServletResponse response = send(createRequest(merchant, key("create"), amountMinor, "Synthetic order"));
    return json(response).path("paymentIntent").path("id").asText();
  }

  String createCapturedIntent(MockHttpSession merchant, long amountMinor) throws Exception {
    String intentId = createIntent(merchant, amountMinor);
    send(actionRequest(merchant, intentId, "authorize", key("authorize")));
    send(actionRequest(merchant, intentId, "capture", key("capture")));
    return intentId;
  }

  /** Releases every call at the same instant so the requests genuinely overlap. */
  List<MockHttpServletResponse> overlap(List<Callable<MockHttpServletResponse>> calls) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(calls.size());
    CountDownLatch ready = new CountDownLatch(calls.size());
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<MockHttpServletResponse>> futures = calls.stream().map(call -> executor.submit(() -> {
        ready.countDown();
        start.await(WAIT_SECONDS, TimeUnit.SECONDS);
        return call.call();
      })).toList();
      ready.await(WAIT_SECONDS, TimeUnit.SECONDS);
      start.countDown();
      java.util.ArrayList<MockHttpServletResponse> responses = new java.util.ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        responses.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
      }
      return responses;
    } finally {
      executor.shutdownNow();
    }
  }
}
