package com.paybridge.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
  private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,80}");
  private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,80}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String suppliedId = request.getHeader("X-Request-ID");
    String requestId = suppliedId != null && VALID_REQUEST_ID.matcher(suppliedId).matches()
        ? suppliedId
        : UUID.randomUUID().toString();
    String suppliedTraceId = request.getHeader("X-Trace-ID");
    String traceId = suppliedTraceId != null && VALID_TRACE_ID.matcher(suppliedTraceId).matches()
        ? suppliedTraceId
        : UUID.randomUUID().toString();
    request.setAttribute("requestId", requestId);
    request.setAttribute("traceId", traceId);
    response.setHeader("X-Request-ID", requestId);
    response.setHeader("X-Trace-ID", traceId);
    MDC.put("requestId", requestId);
    MDC.put("traceId", traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove("requestId");
      MDC.remove("traceId");
    }
  }
}