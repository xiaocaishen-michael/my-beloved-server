package com.mbw.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-request correlation ID filter.
 *
 * <p>Reads {@code X-Request-Id} from incoming request, generates a UUID if
 * absent, propagates it via SLF4J {@link MDC} (key {@code requestId}) so
 * Logback can render it in every log line, and echoes the value back via the
 * response header for client-side correlation.
 *
 * <p>Filter ordered at {@link Ordered#HIGHEST_PRECEDENCE} so MDC is populated
 * before any other filter (Spring Security, etc.) emits logs.
 *
 * <p>Per ADR-0011 / Observability baseline: this is the entry point for the
 * "穷人版 trace" until full OpenTelemetry lands at M2.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER_NAME);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
