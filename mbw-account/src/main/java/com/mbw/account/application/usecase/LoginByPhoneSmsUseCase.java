package com.mbw.account.application.usecase;

import com.mbw.account.application.command.LoginByPhoneSmsCommand;
import com.mbw.account.application.result.LoginByPhoneSmsResult;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.PhonePolicy;
import com.mbw.account.domain.service.RefreshTokenHasher;
import com.mbw.account.domain.service.TimingDefenseExecutor;
import com.mbw.account.domain.service.TokenIssuer;
import com.mbw.shared.api.sms.AttemptOutcome;
import com.mbw.shared.api.sms.SmsCodeService;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Login via phone + SMS verification code" use case (FR-002 / FR-003 /
 * FR-004 / FR-006 / FR-010 / FR-011).
 *
 * <p>The entire pipeline runs inside
 * {@link TimingDefenseExecutor#executeInConstantTime} (FR-011 + SC-003)
 * so all response paths look identical to a wall-clock observer:
 * registered+code-correct, registered+code-wrong, unregistered,
 * FROZEN — all return at {@link #TIMING_TARGET} (600ms, matching SC-001
 * P95 budget; the overall pipeline must stay well below this so the
 * pad has headroom).
 *
 * <p>Internal flow (FR-010 ordered):
 *
 * <ol>
 *   <li>{@link PhonePolicy#validate} (FR-001 — same E.164 format as
 *       register)
 *   <li>{@code login:<phone>} 24h-5 rate-limit gate (FR-006 4th tier)
 *   <li>{@link SmsCodeService#verify}: success consumes the code; on
 *       failure → {@link InvalidCredentialsException} (FR-006)
 *   <li>{@link AccountRepository#findByPhone}: account not found →
 *       {@link InvalidCredentialsException} (FR-003 + FR-006
 *       anti-enumeration; fold "未注册" into the same error byte-shape
 *       as "码错")
 *   <li>{@link AccountStateMachine#canLogin}: status != ACTIVE →
 *       {@link InvalidCredentialsException} (FR-003)
 *   <li>{@link TransactionTemplate}: write {@code last_login_at}
 *       (FR-004) + sign access/refresh tokens (FR-010 atomicity:
 *       token failure rolls back DB write)
 * </ol>
 *
 * <p>No password verification (vs login-by-password): SMS-code success
 * is the proof of identity. Refresh token persistence comes in
 * Phase 1.3 (refresh-token use case) — for now {@link TokenIssuer}
 * returns the raw 256-bit token and the use case does not yet
 * write {@code RefreshTokenRecord}.
 */
@Service
public class LoginByPhoneSmsUseCase {

    static final String RATE_LIMIT_KEY_PREFIX = "login:";
    static final Bandwidth LOGIN_PER_PHONE_24H = Bandwidth.builder()
            .capacity(5)
            .refillIntervally(5, Duration.ofHours(24))
            .build();
    static final Duration TIMING_TARGET = Duration.ofMillis(400);
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private final RateLimitService rateLimitService;
    private final SmsCodeService smsCodeService;
    private final AccountRepository accountRepository;
    private final TokenIssuer tokenIssuer;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TransactionTemplate transactionTemplate;

    public LoginByPhoneSmsUseCase(
            RateLimitService rateLimitService,
            SmsCodeService smsCodeService,
            AccountRepository accountRepository,
            TokenIssuer tokenIssuer,
            RefreshTokenRepository refreshTokenRepository,
            TransactionTemplate transactionTemplate) {
        this.rateLimitService = rateLimitService;
        this.smsCodeService = smsCodeService;
        this.accountRepository = accountRepository;
        this.tokenIssuer = tokenIssuer;
        this.refreshTokenRepository = refreshTokenRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public LoginByPhoneSmsResult execute(LoginByPhoneSmsCommand cmd) {
        return TimingDefenseExecutor.executeInConstantTime(TIMING_TARGET, () -> doExecute(cmd));
    }

    private LoginByPhoneSmsResult doExecute(LoginByPhoneSmsCommand cmd) {
        PhoneNumber phone = PhonePolicy.validate(cmd.phone());

        rateLimitService.consumeOrThrow(RATE_LIMIT_KEY_PREFIX + phone.e164(), LOGIN_PER_PHONE_24H);

        AttemptOutcome verifyResult = smsCodeService.verify(phone.e164(), cmd.code());
        if (!verifyResult.success()) {
            throw new InvalidCredentialsException();
        }

        Optional<Account> accountOpt = accountRepository.findByPhone(phone);
        if (accountOpt.isEmpty()) {
            // Anti-enumeration: phone not registered folds into the same
            // INVALID_CREDENTIALS shape as "wrong code" / "FROZEN".
            throw new InvalidCredentialsException();
        }
        Account account = accountOpt.get();

        if (!AccountStateMachine.canLogin(account)) {
            throw new InvalidCredentialsException();
        }

        return transactionTemplate.execute(status -> persistLogin(account));
    }

    private LoginByPhoneSmsResult persistLogin(Account account) {
        Instant now = Instant.now();

        // FR-004: targeted UPDATE last_login_at + updated_at
        accountRepository.updateLastLoginAt(account.id(), now);

        // FR-010 atomicity: token signing inside same tx → if either
        // signAccess/signRefresh throws, the lastLoginAt UPDATE rolls back.
        String access = tokenIssuer.signAccess(account.id());
        String refresh = tokenIssuer.signRefresh();

        // Phase 1.3 retrofit: persist the refresh token (FR-009 of 1.3)
        refreshTokenRepository.save(RefreshTokenRecord.createActive(
                RefreshTokenHasher.hash(refresh), account.id(), now.plus(REFRESH_TOKEN_TTL), now));

        return new LoginByPhoneSmsResult(account.id().value(), access, refresh);
    }
}
