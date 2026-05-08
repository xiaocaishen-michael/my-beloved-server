package com.mbw.account.application.usecase;

import com.mbw.account.application.command.RefreshTokenCommand;
import com.mbw.account.application.result.RefreshTokenResult;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.IpAddress;
import com.mbw.account.domain.model.RefreshTokenHash;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.RefreshTokenHasher;
import com.mbw.account.domain.service.TokenIssuer;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Refresh access + refresh tokens via a still-active refresh token"
 * use case (Phase 1.3, FR-002 / FR-004 / FR-005 / FR-006 / FR-008).
 *
 * <p>Pipeline (per spec.md FR-004):
 *
 * <ol>
 *   <li>Rate limit: {@code refresh:&lt;ip&gt;} 60s/100 + {@code refresh:&lt;token_hash&gt;} 60s/5
 *   <li>Hash the raw input + look up by hash + check {@code isActive(now)}
 *   <li>Look up the linked Account + check {@link AccountStateMachine#canLogin}
 *   <li>Rotate inside a tx: sign new access + new refresh, persist new
 *       record, revoke the old record. Token signing failure rolls back
 *       the DB writes (FR-008 atomicity).
 * </ol>
 *
 * <p>All failure modes (record not found / expired / already revoked /
 * account FROZEN / account deleted) collapse into
 * {@link InvalidCredentialsException} per spec.md FR-006 — same
 * byte-shape as the wrong-code / wrong-password paths from 1.1 / 1.2.
 */
@Service
public class RefreshTokenUseCase {

    static final String IP_RATE_LIMIT_KEY_PREFIX = "refresh:";
    static final Bandwidth REFRESH_PER_IP_60S = Bandwidth.builder()
            .capacity(100)
            .refillIntervally(100, Duration.ofSeconds(60))
            .build();
    static final Bandwidth REFRESH_PER_TOKEN_60S = Bandwidth.builder()
            .capacity(5)
            .refillIntervally(5, Duration.ofSeconds(60))
            .build();
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private final RateLimitService rateLimitService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccountRepository accountRepository;
    private final TokenIssuer tokenIssuer;
    private final TransactionTemplate transactionTemplate;

    public RefreshTokenUseCase(
            RateLimitService rateLimitService,
            RefreshTokenRepository refreshTokenRepository,
            AccountRepository accountRepository,
            TokenIssuer tokenIssuer,
            TransactionTemplate transactionTemplate) {
        this.rateLimitService = rateLimitService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.accountRepository = accountRepository;
        this.tokenIssuer = tokenIssuer;
        this.transactionTemplate = transactionTemplate;
    }

    public RefreshTokenResult execute(RefreshTokenCommand cmd) {
        // Step 1: rate limit gates (IP first, then per-token)
        rateLimitService.consumeOrThrow(IP_RATE_LIMIT_KEY_PREFIX + cmd.clientIp(), REFRESH_PER_IP_60S);

        // Step 2: hash the raw input + look up
        RefreshTokenHash hash = RefreshTokenHasher.hash(cmd.rawRefreshToken());
        rateLimitService.consumeOrThrow(IP_RATE_LIMIT_KEY_PREFIX + hash.value(), REFRESH_PER_TOKEN_60S);

        Instant now = Instant.now();
        Optional<RefreshTokenRecord> recordOpt = refreshTokenRepository.findByTokenHash(hash);
        if (recordOpt.isEmpty() || !recordOpt.get().isActive(now)) {
            // Anti-enumeration: not-found / expired / already-revoked all
            // fold to the same INVALID_CREDENTIALS shape (FR-006).
            throw new InvalidCredentialsException();
        }
        RefreshTokenRecord oldRecord = recordOpt.get();

        // Step 3: check linked account
        Optional<Account> accountOpt = accountRepository.findById(oldRecord.accountId());
        if (accountOpt.isEmpty() || !AccountStateMachine.canLogin(accountOpt.get())) {
            throw new InvalidCredentialsException();
        }

        // Step 4: rotate inside a tx
        return transactionTemplate.execute(status -> {
            // Per device-management spec FR-012: rotation inherits the
            // parent row's device_id / device_name / device_type /
            // login_method so the lineage of "which device first logged
            // in via which method" survives the chain. Only ip_address
            // is updated to the new request's IP — that's the whole
            // point of "last refresh location".
            String newAccess = tokenIssuer.signAccess(oldRecord.accountId(), oldRecord.deviceId());
            String newRefresh = tokenIssuer.signRefresh();
            RefreshTokenHash newHash = RefreshTokenHasher.hash(newRefresh);

            // Race-defense: revoke FIRST and check affected rows. If 0,
            // a concurrent rotation already won — abort by throwing
            // INVALID_CREDENTIALS so the surrounding tx rolls back
            // before we insert the duplicate record.
            int revoked = refreshTokenRepository.revoke(oldRecord.id(), now);
            if (revoked == 0) {
                throw new InvalidCredentialsException();
            }
            refreshTokenRepository.save(RefreshTokenRecord.createActive(
                    newHash,
                    oldRecord.accountId(),
                    oldRecord.deviceId(),
                    oldRecord.deviceName(),
                    oldRecord.deviceType(),
                    IpAddress.ofNullable(cmd.clientIp()),
                    oldRecord.loginMethod(),
                    now.plus(REFRESH_TOKEN_TTL),
                    now));

            return new RefreshTokenResult(oldRecord.accountId().value(), newAccess, newRefresh);
        });
    }
}
