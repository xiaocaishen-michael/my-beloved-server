package com.mbw.account.application.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configurable bandwidth for the {@code auth:<phone>} bucket consumed by
 * {@code UnifiedPhoneSmsAuthUseCase} (FR-007 brute-force defence).
 *
 * <p>Production default is intentionally tight (5 attempts per 24h, success
 * + failure both count) to make credential stuffing economically unviable.
 * In the dev profile this gets relaxed via {@code application-dev.yml} so
 * iterative E2E testing doesn't burn through the bucket and lock the test
 * phone for 24h.
 *
 * <p>Picked up via {@code @ConfigurationPropertiesScan} on
 * {@code MbwApplication}, so no explicit {@code @EnableConfigurationProperties}
 * needed.
 */
@ConfigurationProperties(prefix = "mbw.auth.rate-limit")
public record AuthRateLimitProperties(@DefaultValue("5") int capacity, @DefaultValue("24h") Duration period) {}
