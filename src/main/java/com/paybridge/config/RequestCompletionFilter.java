package com.paybridge.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RequestCompletionFilter extends OncePerRequestFilter {
  private static final Logger LOGGER = LoggerFactory.getLogger(RequestCompletionFilter.class);
  private static final Pattern INTENT_ROUTE = Pattern.compile("^(/api/v1/payment-intents/)[^/]+(?=/|$)");
  private final BuildProperties buildProperties;

  public RequestCompletionFilter(BuildProperties buildProperties) {
    this.buildProperties = buildProperties;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    long startedAt = System.nanoTime();
    try {
      filterChain.doFilter(request, response);
    } finally {
      if (request.getRequestURI().startsWith("/api/v1/")) {
        logCompletion(request, response, startedAt);
      }
    }
  }

  private void logCompletion(
      HttpServletRequest request,
      HttpServletResponse response,
      long startedAt) {
    int status = response.getStatus();
    String outcome = status >= 400 ? "failure" : "success";
    String eventName = status >= 400 ? "api.request.failed" : "api.request.completed";
    LoggingEventBuilder event = status >= 500
        ? LOGGER.atError()
        : status >= 400 ? LOGGER.atWarn() : LOGGER.atInfo();

    event.addKeyValue("service", "paybridge-api")
      .addKeyValue("version", buildProperties.getVersion())
        .addKeyValue("eventName", eventName)
        .addKeyValue("requestId", request.getAttribute("requestId"))
        .addKeyValue("traceId", request.getAttribute("traceId"))
        .addKeyValue("httpMethod", request.getMethod())
        .addKeyValue("routeTemplate", routeTemplate(request))
        .addKeyValue("httpStatus", status)
        .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
        .addKeyValue("outcome", outcome);

    Object errorCode = request.getAttribute("errorCode");
    if (errorCode != null) event.addKeyValue("errorCode", errorCode);
    event.log("HTTP request completed");
  }

  private String routeTemplate(HttpServletRequest request) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    if (pattern instanceof String route) return route;
    return INTENT_ROUTE.matcher(request.getRequestURI()).replaceFirst("$1{intentId}");
  }
}