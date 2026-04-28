package com.mbw.shared.web;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Cross-module rate-limit service backed by Bucket4j core (ADR-0011).
 *
 * <p>M1.1 single-instance: in-memory {@link ConcurrentHashMap} of buckets
 * (each scoped by an arbitrary string key — typically {@code "<scenario>:<subject>"}
 * such as {@code "sms:+8613800138000"} or {@code "login:192.168.1.1"}).
 * M2 double-node: replace this map with bucket4j-redis-backed proxy without
 * touching call sites.
 *
 * <p>Usage from business modules:
 * <pre>{@code
 * Bandwidth limit = Bandwidth.builder()
 *     .capacity(5).refillIntervally(5, Duration.ofMinutes(1)).build();
 * rateLimitService.consumeOrThrow("login:" + clientIp, limit);
 * }</pre>
 *
 * <p>Specific limits per business scenario (SMS, login, registration) are
 * defined in module-specific specs (e.g. {@code spec/account/login/spec.md})
 * — this service only provides the framework; see
 * {@code spec/_baseline/rate-limit-policy.md}.
 */
@Service
public class RateLimitService {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Consumes one token for the given key. If the bucket is exhausted,
     * throws {@link RateLimitedException} carrying the suggested retry delay.
     * Bucket is created lazily with the supplied bandwidth on first call;
     * subsequent calls reuse it (so changing bandwidth at runtime requires
     * resetting via {@link #reset(String)}).
     */
    public void consumeOrThrow(String key, Bandwidth bandwidth) {
        Bucket bucket = buckets.computeIfAbsent(
                key, k -> Bucket.builder().addLimit(bandwidth).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            Duration retryAfter = Duration.ofNanos(probe.getNanosToWaitForRefill());
            throw new RateLimitedException(key, retryAfter);
        }
    }

    /**
     * Drops the bucket for the given key. Useful in tests or after a
     * privileged manual reset.
     */
    public void reset(String key) {
        buckets.remove(key);
    }
}
