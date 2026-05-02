package com.mbw.account.application.usecase;

import com.mbw.account.application.command.LoginByPasswordCommand;
import com.mbw.account.application.result.LoginByPasswordResult;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.PasswordHash;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.account.domain.service.PasswordHasher;
import com.mbw.account.domain.service.PhonePolicy;
import com.mbw.account.domain.service.TimingDefenseExecutor;
import com.mbw.account.domain.service.TokenIssuer;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Login via phone + password" use case (FR-002 / FR-003 / FR-004 /
 * FR-006 / FR-007 / FR-009 / FR-010).
 *
 * <p>Anti-enumeration design (FR-009): all four failure modes
 * (wrong password / unregistered phone / no password set / FROZEN)
 * collapse into a single {@code INVALID_CREDENTIALS} response shape
 * AND identical wall-clock cost. The latter is achieved by routing
 * through {@link TimingDefenseExecutor#executeWithBCryptVerify} so a
 * BCrypt verify always runs — using the real password hash if one
 * exists, otherwise {@link TimingDefenseExecutor#DUMMY_HASH}.
 *
 * <p>Pipeline:
 *
 * <ol>
 *   <li>{@link PhonePolicy#validate} (FR-001 — same E.164 format as
 *       register)
 *   <li>{@code login:<phone>} 24h-5 rate-limit gate (FR-007, shared
 *       failure-counter bucket with login-by-phone-sms)
 *   <li>{@code auth:<ip>} 24h-100 rate-limit gate (FR-007, IP-tier)
 *   <li>{@link TimingDefenseExecutor#executeWithBCryptVerify}:
 *       <ul>
 *         <li>Look up Account by phone → if missing or non-ACTIVE,
 *             return {@code DUMMY_HASH}
 *         <li>Otherwise look up PASSWORD credential → if missing,
 *             return {@code DUMMY_HASH}; if present, return its hash
 *         <li>BCrypt verify always runs against the resolved hash;
 *             match → onMatch (sign tokens + write last_login_at);
 *             mismatch → onMismatch (throw INVALID_CREDENTIALS)
 *       </ul>
 * </ol>
 *
 * <p>Atomicity (FR-010): {@code @Transactional(rollbackFor =
 * Throwable.class)} — token signing failure rolls back
 * {@code last_login_at} write.
 */
@Service
public class LoginByPasswordUseCase {

    static final String LOGIN_RATE_LIMIT_KEY_PREFIX = "login:";
    static final String AUTH_IP_RATE_LIMIT_KEY_PREFIX = "auth:";
    static final Bandwidth LOGIN_PER_PHONE_24H = Bandwidth.builder()
            .capacity(5)
            .refillIntervally(5, Duration.ofHours(24))
            .build();
    static final Bandwidth AUTH_PER_IP_24H = Bandwidth.builder()
            .capacity(100)
            .refillIntervally(100, Duration.ofHours(24))
            .build();

    private final RateLimitService rateLimitService;
    private final AccountRepository accountRepository;
    private final CredentialRepository credentialRepository;
    private final PasswordHasher passwordHasher;
    private final TokenIssuer tokenIssuer;
    private final TransactionTemplate transactionTemplate;

    public LoginByPasswordUseCase(
            RateLimitService rateLimitService,
            AccountRepository accountRepository,
            CredentialRepository credentialRepository,
            PasswordHasher passwordHasher,
            TokenIssuer tokenIssuer,
            TransactionTemplate transactionTemplate) {
        this.rateLimitService = rateLimitService;
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.passwordHasher = passwordHasher;
        this.tokenIssuer = tokenIssuer;
        this.transactionTemplate = transactionTemplate;
    }

    public LoginByPasswordResult execute(LoginByPasswordCommand cmd) {
        PhoneNumber phone = PhonePolicy.validate(cmd.phone());

        rateLimitService.consumeOrThrow(LOGIN_RATE_LIMIT_KEY_PREFIX + phone.e164(), LOGIN_PER_PHONE_24H);
        rateLimitService.consumeOrThrow(AUTH_IP_RATE_LIMIT_KEY_PREFIX + cmd.clientIp(), AUTH_PER_IP_24H);

        return TimingDefenseExecutor.executeWithBCryptVerify(
                passwordHasher, cmd.password(), () -> resolveHashOrDummy(phone), () -> persistLogin(phone), () -> {
                    throw new InvalidCredentialsException();
                });
    }

    private PasswordHash resolveHashOrDummy(PhoneNumber phone) {
        Optional<Account> accountOpt = accountRepository.findByPhone(phone);
        if (accountOpt.isEmpty() || !AccountStateMachine.canLogin(accountOpt.get())) {
            return TimingDefenseExecutor.DUMMY_HASH;
        }
        return credentialRepository
                .findPasswordCredentialByAccountId(accountOpt.get().id())
                .map(pc -> pc.hash())
                .orElse(TimingDefenseExecutor.DUMMY_HASH);
    }

    private LoginByPasswordResult persistLogin(PhoneNumber phone) {
        return transactionTemplate.execute(status -> {
            // Re-fetch the account inside the transaction. The lookup in
            // resolveHashOrDummy ran outside the tx — we don't reuse its
            // result so the persistence of last_login_at + tokens is a
            // single transactional unit (FR-010 atomicity).
            Account account = accountRepository.findByPhone(phone).orElseThrow();
            String access = tokenIssuer.signAccess(account.id());
            String refresh = tokenIssuer.signRefresh();
            accountRepository.updateLastLoginAt(account.id(), Instant.now());
            return new LoginByPasswordResult(account.id().value(), access, refresh);
        });
    }
}
