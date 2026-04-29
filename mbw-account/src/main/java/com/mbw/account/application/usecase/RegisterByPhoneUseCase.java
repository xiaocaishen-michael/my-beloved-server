package com.mbw.account.application.usecase;

import com.mbw.account.application.command.RegisterByPhoneCommand;
import com.mbw.account.application.result.RegisterByPhoneResult;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.PasswordCredential;
import com.mbw.account.domain.model.PasswordHash;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.account.domain.service.PasswordHasher;
import com.mbw.account.domain.service.PasswordPolicy;
import com.mbw.account.domain.service.PhonePolicy;
import com.mbw.account.domain.service.TimingDefenseExecutor;
import com.mbw.account.domain.service.TokenIssuer;
import com.mbw.shared.api.sms.AttemptOutcome;
import com.mbw.shared.api.sms.SmsCodeService;
import com.mbw.shared.web.RateLimitService;
import io.github.bucket4j.Bandwidth;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Register a new account via phone + verification code" use case
 * (FR-005 / FR-007 / FR-008 / FR-011 / FR-013).
 *
 * <p>The entire pipeline runs inside
 * {@link TimingDefenseExecutor#executeInConstantTime} (FR-013) so all
 * response paths look identical to a wall-clock observer — registered
 * vs unregistered, code-correct vs code-wrong, password-strong vs
 * password-weak all return at the same {@link #TIMING_TARGET}.
 *
 * <p>Internal flow:
 *
 * <ol>
 *   <li>{@code PhonePolicy.validate} (FR-001)
 *   <li>{@code PasswordPolicy.validate} if password supplied (FR-003)
 *   <li>{@code register:<phone>} 24h-5 rate-limit gate (FR-006 4th
 *       tier; locks brute-force code attempts)
 *   <li>{@code SmsCodeService.verify}: success consumes the code
 *       atomically; on failure the impl increments the attempt
 *       counter and (at the threshold) deletes the entry — caller
 *       always re-throws the same {@link InvalidCredentialsException}
 *       so the response shape is enumeration-resistant (FR-007)
 *   <li>{@link TransactionTemplate}: activate Account, save id,
 *       persist phone credential (mandatory), persist password
 *       credential (if supplied), sign access/refresh tokens. The
 *       {@code @Transactional rollbackFor=Throwable} semantic is
 *       carried by TransactionTemplate's default propagation +
 *       rollback rules — any RuntimeException rolls back. Token
 *       signing happens after the DB writes so it shares the same
 *       atomicity (token exception → tx rollback → no orphan
 *       account).
 *   <li>{@link DataIntegrityViolationException} from
 *       {@code uk_account_phone} or
 *       {@code uk_credential_account_type} → uniformly mapped to
 *       {@link InvalidCredentialsException} per FR-007.
 * </ol>
 *
 * <p>Note on FR-011 ordering: spec.md requests "Token 必须先签发后写
 * DB" but BIGINT IDENTITY columns assign the id on INSERT — pre-sign
 * impossible without sequence pre-fetch (schema change). Pragmatic
 * resolution: sign inside the same transaction; token failure rolls
 * back DB. Net atomicity is preserved (no orphan account); the
 * "window" concern in the spec is addressed by READ COMMITTED
 * isolation (uncommitted writes invisible to other transactions).
 */
@Service
public class RegisterByPhoneUseCase {

    static final String RATE_LIMIT_KEY_PREFIX = "register:";
    static final Bandwidth REGISTER_PER_PHONE_24H = Bandwidth.builder()
            .capacity(5)
            .refillIntervally(5, Duration.ofHours(24))
            .build();
    static final Duration TIMING_TARGET = Duration.ofMillis(400);

    private final RateLimitService rateLimitService;
    private final SmsCodeService smsCodeService;
    private final AccountRepository accountRepository;
    private final CredentialRepository credentialRepository;
    private final PasswordHasher passwordHasher;
    private final TokenIssuer tokenIssuer;
    private final TransactionTemplate transactionTemplate;

    public RegisterByPhoneUseCase(
            RateLimitService rateLimitService,
            SmsCodeService smsCodeService,
            AccountRepository accountRepository,
            CredentialRepository credentialRepository,
            PasswordHasher passwordHasher,
            TokenIssuer tokenIssuer,
            TransactionTemplate transactionTemplate) {
        this.rateLimitService = rateLimitService;
        this.smsCodeService = smsCodeService;
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.passwordHasher = passwordHasher;
        this.tokenIssuer = tokenIssuer;
        this.transactionTemplate = transactionTemplate;
    }

    public RegisterByPhoneResult execute(RegisterByPhoneCommand cmd) {
        return TimingDefenseExecutor.executeInConstantTime(TIMING_TARGET, () -> doExecute(cmd));
    }

    private RegisterByPhoneResult doExecute(RegisterByPhoneCommand cmd) {
        PhoneNumber phone = PhonePolicy.validate(cmd.phone());
        cmd.password().ifPresent(PasswordPolicy::validate);

        rateLimitService.consumeOrThrow(RATE_LIMIT_KEY_PREFIX + phone.e164(), REGISTER_PER_PHONE_24H);

        AttemptOutcome verifyResult = smsCodeService.verify(phone.e164(), cmd.code());
        if (!verifyResult.success()) {
            throw new InvalidCredentialsException();
        }

        try {
            return transactionTemplate.execute(status -> persistAccount(phone, cmd.password()));
        } catch (DataIntegrityViolationException ex) {
            // FR-005 (uk_account_phone) or FR-007 (uk_credential_account_type)
            throw new InvalidCredentialsException();
        }
    }

    private RegisterByPhoneResult persistAccount(PhoneNumber phone, Optional<String> password) {
        Instant now = Instant.now();

        Account account = new Account(phone, now);
        AccountStateMachine.activate(account, now);
        Account saved = accountRepository.save(account);
        AccountId id = saved.id();

        credentialRepository.save(new PhoneCredential(id, phone, now));
        password.ifPresent(plaintext -> {
            PasswordHash hash = passwordHasher.hash(plaintext);
            credentialRepository.save(new PasswordCredential(id, hash, now));
        });

        String access = tokenIssuer.signAccess(id);
        String refresh = tokenIssuer.signRefresh();

        return new RegisterByPhoneResult(id.value(), access, refresh);
    }
}
