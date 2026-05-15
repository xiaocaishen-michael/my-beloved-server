package com.mbw.shared.web;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cross-module rate-limit service backed by Bucket4j with a Redis-backed
 * ProxyManager (ADR-0011 amended 2026-04-28: Redis from M1.1, no
 * in-memory phase).
 *
 * <p>Public API ({@link #consumeOrThrow} / {@link #reset}) is unchanged
 * from the previous in-memory implementation; business modules see no
 * difference. The Redis ProxyManager bean is wired in
 * {@code mbw-app/infrastructure/ratelimit/RateLimitConfig}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * Bandwidth limit = Bandwidth.builder()
 *     .capacity(5).refillIntervally(5, Duration.ofMinutes(1)).build();
 * rateLimitService.consumeOrThrow("login:" + clientIp, limit);
 * }</pre>
 *
 * <p>Per-scenario rules live in module-specific specs (e.g.
 * {@code specs/auth/register-by-phone/spec.md}); this service is
 * framework only. See {@code spec/_baseline/rate-limit-policy.md}.
 *
 * <p><b>Fail-closed semantics:</b> if Redis is unreachable or returns an
 * error, the consume call throws {@link RateLimitedException} with
 * {@link Duration#ZERO} retryAfter (HTTP 429 outward). This denies
 * service rather than silently allowing all traffic, matching spec
 * FR-006 + the project rate-limit policy. Operators must monitor Redis
 * availability separately (alerts wire up in M2).
 */
@Service
public class RateLimitService {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitService.class);

    private final ProxyManager<String> proxyManager;

    public RateLimitService(ProxyManager<String> proxyManager) {
        this.proxyManager = proxyManager;
    }

    /**
     * Consumes one token for {@code key}. Throws {@link RateLimitedException}
     * with the suggested retry delay when the bucket is exhausted, or with
     * {@link Duration#ZERO} when Redis is unavailable (fail-closed).
     *
     * <p>Fail-closed catches {@link RuntimeException} on purpose: bucket4j
     * wraps Lettuce / IO / serialization errors in a variety of unchecked
     * subtypes, and the fail-closed contract must be uniform across all of
     * them. Listing each subtype individually would drift as the libraries
     * evolve.
     */
    @SuppressWarnings({"checkstyle:IllegalCatch", "PMD.AvoidCatchingGenericException"})
    public void consumeOrThrow(String key, Bandwidth bandwidth) {
        BucketProxy bucket;
        ConsumptionProbe probe;
        try {
            bucket = proxyManager.builder().build(key, () -> BucketConfiguration.builder()
                    .addLimit(bandwidth)
                    .build());
            probe = bucket.tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException ex) {
            LOG.error("Redis unavailable for rate-limit key={}; failing closed", key, ex);
            throw new RateLimitedException(key, Duration.ZERO);
        }
        if (!probe.isConsumed()) {
            throw new RateLimitedException(key, Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }
    }

    /**
     * Drops the bucket proxy for {@code key}. Useful in tests and after a
     * privileged manual reset. Best-effort: failures are logged and
     * swallowed (reset is non-critical; next consume rebuilds state).
     *
     * <p>RuntimeException catch follows the same uniform-fail-closed rationale
     * as {@link #consumeOrThrow}.
     */
    @SuppressWarnings({"checkstyle:IllegalCatch", "PMD.AvoidCatchingGenericException"})
    public void reset(String key) {
        try {
            proxyManager.removeProxy(key);
        } catch (RuntimeException ex) {
            LOG.warn("Redis unavailable while resetting rate-limit key={}; swallowed", key, ex);
        }
    }
}
