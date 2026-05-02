package com.mbw.account.application.command;

import java.util.Objects;

/**
 * Input for {@code RefreshTokenUseCase} (Phase 1.3).
 *
 * <p>{@code rawRefreshToken} is the 256-bit base64url string the
 * client received at issuance (register / login-by-*). Server hashes
 * it via {@code RefreshTokenHasher} for the {@code findByTokenHash}
 * lookup; the raw value never lands in DB or logs.
 *
 * <p>{@code clientIp} feeds the {@code refresh:&lt;ip&gt;} rate-limit
 * bucket per spec.md FR-005.
 */
public record RefreshTokenCommand(String rawRefreshToken, String clientIp) {

    public RefreshTokenCommand {
        Objects.requireNonNull(rawRefreshToken, "rawRefreshToken must not be null");
        Objects.requireNonNull(clientIp, "clientIp must not be null");
    }
}
