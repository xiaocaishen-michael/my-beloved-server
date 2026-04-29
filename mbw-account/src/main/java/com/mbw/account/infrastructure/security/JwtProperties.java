package com.mbw.account.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised JWT configuration (FR-008 secret management).
 *
 * <p>Bound to {@code mbw.auth.jwt.secret} which Docker Compose / K8s
 * inject via the {@code MBW_AUTH_JWT_SECRET} environment variable.
 * {@code @Validated @NotBlank} makes Spring fail-fast at boot when the
 * env var is missing — never silently fallback to a default secret
 * (the explicit anti-pattern called out in spec.md FR-008).
 *
 * <p>HS256 signing requires a secret ≥ 256 bits. Length is enforced in
 * the compact constructor so misconfiguration (a too-short secret in
 * any environment) fails-fast rather than later at first sign attempt.
 */
@ConfigurationProperties(prefix = "mbw.auth.jwt")
@Validated
public record JwtProperties(@NotBlank String secret) {

    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret != null && secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least " + MIN_SECRET_BYTES + " bytes (256 bits) for HS256");
        }
    }
}
