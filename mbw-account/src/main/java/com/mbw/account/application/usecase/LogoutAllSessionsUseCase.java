package com.mbw.account.application.usecase;

import com.mbw.account.application.command.LogoutAllSessionsCommand;
import com.mbw.account.application.result.LogoutAllSessionsResult;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Logout all sessions" use case (Phase 1.4, FR-002 / FR-004 /
 * FR-005 / FR-006).
 *
 * <p>Bulk-revokes every active refresh token for the given account so
 * a stolen / lost device cannot keep refreshing access tokens. Pure
 * server-side state change — the response is 204 with no body.
 *
 * <p>Per spec.md edge cases, the operation is idempotent: zero, one, or
 * many active records all complete normally; the affected count is
 * logged only.
 */
@Service
public class LogoutAllSessionsUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(LogoutAllSessionsUseCase.class);

    static final String ACCOUNT_RATE_LIMIT_KEY_PREFIX = "logout-all:";
    static final Bandwidth LOGOUT_ALL_PER_ACCOUNT_60S = Bandwidth.builder()
            .capacity(5)
            .refillIntervally(5, Duration.ofSeconds(60))
            .build();
    static final Bandwidth LOGOUT_ALL_PER_IP_60S = Bandwidth.builder()
            .capacity(50)
            .refillIntervally(50, Duration.ofSeconds(60))
            .build();

    private final RateLimitService rateLimitService;
    private final RefreshTokenRepository refreshTokenRepository;

    public LogoutAllSessionsUseCase(RateLimitService rateLimitService, RefreshTokenRepository refreshTokenRepository) {
        this.rateLimitService = rateLimitService;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional(rollbackFor = Throwable.class)
    public LogoutAllSessionsResult execute(LogoutAllSessionsCommand cmd) {
        rateLimitService.consumeOrThrow(
                ACCOUNT_RATE_LIMIT_KEY_PREFIX + cmd.accountId().value(), LOGOUT_ALL_PER_ACCOUNT_60S);
        rateLimitService.consumeOrThrow(ACCOUNT_RATE_LIMIT_KEY_PREFIX + cmd.clientIp(), LOGOUT_ALL_PER_IP_60S);

        int revoked = refreshTokenRepository.revokeAllForAccount(cmd.accountId(), Instant.now());
        LOG.info(
                "logout-all completed: accountId={} revokedCount={}",
                cmd.accountId().value(),
                revoked);
        return new LogoutAllSessionsResult(revoked);
    }
}
