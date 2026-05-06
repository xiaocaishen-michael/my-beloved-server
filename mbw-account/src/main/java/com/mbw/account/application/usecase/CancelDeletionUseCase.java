package com.mbw.account.application.usecase;

import com.mbw.account.api.event.AccountDeletionCancelledEvent;
import com.mbw.account.application.command.CancelDeletionCommand;
import com.mbw.account.application.result.CancelDeletionResult;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountSmsCode;
import com.mbw.account.domain.model.AccountSmsCodePurpose;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.AccountSmsCodeRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.PhonePolicy;
import com.mbw.account.domain.service.RefreshTokenHasher;
import com.mbw.account.domain.service.TokenIssuer;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Cancel deletion" use case (cancel-deletion spec § Endpoint 2, M1.3).
 *
 * <p>Public, unauthed entry point — clients in FROZEN state have no
 * tokens. Anti-enumeration (FR-006 / SC-002): five failure modes
 * (phone not registered, account ACTIVE, account ANONYMIZED, FROZEN
 * with grace expired, code mismatch / not found / used) all collapse
 * into a single {@link InvalidCredentialsException} mapped to HTTP 401.
 * A dummy SHA-256 keeps timing indistinguishable across the four
 * phone-class branches that bail before the real hash compare.
 *
 * <p>Transactional boundary (rollbackFor=Throwable): any failure from
 * step 5 onwards rolls back markUsed, status transition, refresh-token
 * persistence, and the outbox event together. Spring Modulith Event
 * Publication Registry persists the outbox row in the same transaction.
 *
 * <ol>
 *   <li>Phone-hash + IP rate-limit gates
 *   <li>findByPhone → 4-class branch (3 dummy + grace-expired) → 401
 *   <li>findActiveByPurposeAndAccountId(CANCEL_DELETION) → empty → 401
 *   <li>SHA-256 hash compare → mismatch → 401
 *   <li>Defensive isActive re-check (guard against concurrent markUsed)
 *   <li>markUsed (persist)
 *   <li>{@link AccountStateMachine#markActiveFromFrozen} (race-safe:
 *       freeze_until &gt; now re-checked; scheduler beat us → 401)
 *   <li>save(account)
 *   <li>publish AccountDeletionCancelledEvent (outbox)
 *   <li>signAccess + signRefresh
 *   <li>persist new refresh_token row
 * </ol>
 */
@Service
public class CancelDeletionUseCase {

    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    static final Bandwidth PER_PHONE_60S = Bandwidth.builder()
            .capacity(5)
            .refillIntervally(5, Duration.ofSeconds(60))
            .build();
    static final Bandwidth PER_IP_60S = Bandwidth.builder()
            .capacity(10)
            .refillIntervally(10, Duration.ofSeconds(60))
            .build();

    private static final Logger LOG = LoggerFactory.getLogger(CancelDeletionUseCase.class);
    private static final HexFormat HEX = HexFormat.of();

    private final RateLimitService rateLimitService;
    private final AccountRepository accountRepository;
    private final AccountSmsCodeRepository smsCodeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenIssuer tokenIssuer;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public CancelDeletionUseCase(
            RateLimitService rateLimitService,
            AccountRepository accountRepository,
            AccountSmsCodeRepository smsCodeRepository,
            RefreshTokenRepository refreshTokenRepository,
            TokenIssuer tokenIssuer,
            ApplicationEventPublisher eventPublisher) {
        this(
                rateLimitService,
                accountRepository,
                smsCodeRepository,
                refreshTokenRepository,
                tokenIssuer,
                eventPublisher,
                Clock.systemUTC());
    }

    CancelDeletionUseCase(
            RateLimitService rateLimitService,
            AccountRepository accountRepository,
            AccountSmsCodeRepository smsCodeRepository,
            RefreshTokenRepository refreshTokenRepository,
            TokenIssuer tokenIssuer,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.rateLimitService = rateLimitService;
        this.accountRepository = accountRepository;
        this.smsCodeRepository = smsCodeRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenIssuer = tokenIssuer;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Throwable.class)
    public CancelDeletionResult execute(CancelDeletionCommand cmd) {
        PhoneNumber phone = PhonePolicy.validate(cmd.phone());
        String phoneHash = sha256Hex(phone.e164());

        rateLimitService.consumeOrThrow("cancel-submit:phone:" + phoneHash, PER_PHONE_60S);
        rateLimitService.consumeOrThrow("cancel-submit:ip:" + cmd.clientIp(), PER_IP_60S);

        Optional<Account> maybeAccount = accountRepository.findByPhone(phone);
        Instant now = Instant.now(clock);

        boolean eligible = maybeAccount
                .map(a -> a.status() == AccountStatus.FROZEN
                        && a.freezeUntil() != null
                        && a.freezeUntil().isAfter(now))
                .orElse(false);

        if (!eligible) {
            // Dummy SHA-256 to align timing with the eligible-path code-hash compare.
            sha256Hex("cancel-deletion-dummy:" + phoneHash);
            throw new InvalidCredentialsException();
        }

        Account account = maybeAccount.get();

        AccountSmsCode codeRecord = smsCodeRepository
                .findActiveByPurposeAndAccountId(AccountSmsCodePurpose.CANCEL_DELETION, account.id(), now)
                .orElseThrow(InvalidCredentialsException::new);

        if (!sha256Hex(cmd.code()).equals(codeRecord.codeHash())) {
            throw new InvalidCredentialsException();
        }

        if (!codeRecord.isActive(now)) {
            throw new InvalidCredentialsException();
        }

        smsCodeRepository.markUsed(codeRecord.id(), now);

        try {
            AccountStateMachine.markActiveFromFrozen(account, now);
        } catch (IllegalStateException ex) {
            // Race with anonymize scheduler: freeze_until > now at step 3 but <= now
            // by the time we reach the state machine. Collapse to 401 (FR-006).
            throw new InvalidCredentialsException();
        }

        accountRepository.save(account);

        eventPublisher.publishEvent(new AccountDeletionCancelledEvent(account.id(), now, now));

        String access = tokenIssuer.signAccess(account.id());
        String refresh = tokenIssuer.signRefresh();

        refreshTokenRepository.save(RefreshTokenRecord.createActive(
                RefreshTokenHasher.hash(refresh), account.id(), now.plus(REFRESH_TOKEN_TTL), now));

        LOG.info("account.deletion.cancelled accountId={}", account.id().value());

        return new CancelDeletionResult(account.id().value(), access, refresh);
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
