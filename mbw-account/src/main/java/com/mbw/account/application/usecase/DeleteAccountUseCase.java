package com.mbw.account.application.usecase;

import com.mbw.account.api.event.AccountDeletionRequestedEvent;
import com.mbw.account.application.command.DeleteAccountCommand;
import com.mbw.account.domain.exception.AccountNotFoundException;
import com.mbw.account.domain.exception.InvalidDeletionCodeException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountSmsCode;
import com.mbw.account.domain.model.AccountSmsCodePurpose;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.AccountSmsCodeRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Delete account" use case (delete-account spec Endpoint 2, M1.3 T8).
 *
 * <p>Transactional boundary: all steps from markUsed through
 * revokeAllForAccount must succeed atomically — any failure rolls back
 * status, freezeUntil, usedAt, and revocations together. Spring
 * Modulith Event Publication Registry persists the outbox event within
 * the same transaction.
 *
 * <ol>
 *   <li>Account + IP rate-limit gates
 *   <li>Find active DELETE_ACCOUNT code — not found → 401
 *   <li>SHA-256 hash comparison — mismatch → 401
 *   <li>Defensive isActive check — expired/used → 401
 *   <li>markUsed (persist)
 *   <li>Load account → markFrozen (freezeUntil = now + 15 days)
 *   <li>Save frozen account
 *   <li>Revoke all refresh tokens
 *   <li>Publish AccountDeletionRequestedEvent (outbox)
 * </ol>
 */
@Service
public class DeleteAccountUseCase {

    static final Duration FREEZE_DURATION = Duration.ofDays(15);

    static final Bandwidth PER_ACCOUNT_60S = Bandwidth.builder()
            .capacity(5)
            .refillIntervally(5, Duration.ofSeconds(60))
            .build();
    static final Bandwidth PER_IP_60S = Bandwidth.builder()
            .capacity(10)
            .refillIntervally(10, Duration.ofSeconds(60))
            .build();

    private static final Logger LOG = LoggerFactory.getLogger(DeleteAccountUseCase.class);
    private static final HexFormat HEX = HexFormat.of();

    private final RateLimitService rateLimitService;
    private final AccountSmsCodeRepository smsCodeRepository;
    private final AccountRepository accountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public DeleteAccountUseCase(
            RateLimitService rateLimitService,
            AccountSmsCodeRepository smsCodeRepository,
            AccountRepository accountRepository,
            RefreshTokenRepository refreshTokenRepository,
            ApplicationEventPublisher eventPublisher) {
        this(
                rateLimitService,
                smsCodeRepository,
                accountRepository,
                refreshTokenRepository,
                eventPublisher,
                Clock.systemUTC());
    }

    DeleteAccountUseCase(
            RateLimitService rateLimitService,
            AccountSmsCodeRepository smsCodeRepository,
            AccountRepository accountRepository,
            RefreshTokenRepository refreshTokenRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.rateLimitService = rateLimitService;
        this.smsCodeRepository = smsCodeRepository;
        this.accountRepository = accountRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Throwable.class)
    public void execute(DeleteAccountCommand cmd) {
        rateLimitService.consumeOrThrow(
                "delete-submit:account:" + cmd.accountId().value(), PER_ACCOUNT_60S);
        rateLimitService.consumeOrThrow("delete-submit:ip:" + cmd.clientIp(), PER_IP_60S);

        Instant now = Instant.now(clock);

        AccountSmsCode codeRecord = smsCodeRepository
                .findActiveByPurposeAndAccountId(AccountSmsCodePurpose.DELETE_ACCOUNT, cmd.accountId(), now)
                .orElseThrow(InvalidDeletionCodeException::new);

        if (!sha256Hex(cmd.code()).equals(codeRecord.codeHash())) {
            throw new InvalidDeletionCodeException();
        }

        // defensive second check — repository pre-filters, but guard against
        // race between load and any concurrent markUsed
        if (!codeRecord.isActive(now)) {
            throw new InvalidDeletionCodeException();
        }

        smsCodeRepository.markUsed(codeRecord.id(), now);

        Account account = accountRepository.findById(cmd.accountId()).orElseThrow(AccountNotFoundException::new);

        Instant freezeUntil = now.plus(FREEZE_DURATION);
        AccountStateMachine.markFrozen(account, freezeUntil, now);
        accountRepository.save(account);

        int revokedCount = refreshTokenRepository.revokeAllForAccount(cmd.accountId(), now);

        eventPublisher.publishEvent(new AccountDeletionRequestedEvent(cmd.accountId(), now, freezeUntil, now));

        LOG.info(
                "account.deletion.requested accountId={} freezeUntil={} revokedRefreshTokens={}",
                cmd.accountId().value(),
                freezeUntil,
                revokedCount);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
