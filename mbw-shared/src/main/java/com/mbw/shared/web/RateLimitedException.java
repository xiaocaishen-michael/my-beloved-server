package com.mbw.shared.web;

import java.time.Duration;

/**
 * Thrown when a rate-limit bucket has no tokens left.
 *
 * <p>Carries {@link #retryAfter} so callers (typically business-module
 * controllers via the rate-limit framework, ADR-0011) don't need to
 * compute it from a {@code ConsumptionProbe}. Mapped to HTTP 429 with the
 * {@code Retry-After} header by {@link GlobalExceptionHandler}.
 */
public class RateLimitedException extends RuntimeException {

    private final Duration retryAfter;
    private final String limitKey;

    public RateLimitedException(String limitKey, Duration retryAfter) {
        super("Rate limit exceeded for: " + limitKey);
        this.limitKey = limitKey;
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    public String getLimitKey() {
        return limitKey;
    }
}
