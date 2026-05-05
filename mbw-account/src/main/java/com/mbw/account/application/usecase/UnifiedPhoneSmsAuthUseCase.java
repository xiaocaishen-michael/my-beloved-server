package com.mbw.account.application.usecase;

import com.mbw.account.application.command.PhoneSmsAuthCommand;
import com.mbw.account.application.config.AuthRateLimitProperties;
import com.mbw.account.application.result.PhoneSmsAuthResult;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.PhoneCredential;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Unified phone-SMS auth" use case (per ADR-0016 + spec
 * {@code phone-sms-auth/spec.md} FR-001 / FR-005 / FR-006 / FR-007 /
 * FR-008 / FR-009).
 *
 * <p>Client-facing single endpoint: server auto-branches on phone
 * existence — replaces previous {@code RegisterByPhoneUseCase} +
 * {@code LoginByPhoneSmsUseCase} + {@code LoginByPasswordUseCase}.
 *
 * <p>The entire pipeline runs inside
 * {@link TimingDefenseExecutor#executeInConstantTime} (FR-006 + SC-003
 * 反枚举字节级一致 + timing defense ≤ 50ms) so all response paths look
 * identical to a wall-clock observer:
 *
 * <ul>
 *   <li>已注册 ACTIVE 成功 → updateLastLoginAt + sign tokens + 200
 *   <li>未注册自动创建成功 → create Account + PhoneCredential + sign
 *       tokens + outbox AccountCreatedEvent + 200
 *   <li>已注册 FROZEN/ANONYMIZED → dummy hash + InvalidCredentialsException
 *   <li>码错 / 码过期 → dummy hash + InvalidCredentialsException
 * </ul>
 *
 * <p>Internal flow (FR-008 ordered, single transaction):
 *
 * <ol>
 *   <li>{@link PhonePolicy#validate} (FR-002 E.164 + mainland)
 *   <li>{@code auth:<phone>} 24h-5 rate-limit gate (FR-007 — replaces
 *       legacy {@code register:} + {@code login:} buckets)
 *   <li>{@link SmsCodeService#verify}: success consumes the code; on
 *       failure → {@link InvalidCredentialsException} (FR-006)
 *   <li>{@link AccountRepository#findByPhone} → branch:
 *       <ul>
 *         <li>empty → register branch ({@link #persistNewAccount})
 *         <li>present + ACTIVE → login branch ({@link #persistLogin})
 *         <li>present + FROZEN/ANONYMIZED →
 *             {@link InvalidCredentialsException} (FR-005 anti-enumeration)
 *       </ul>
 *   <li>{@link DataIntegrityViolationException} from concurrent
 *       same-phone register race → fallthrough to login branch (per
 *       spec CL-004)
 * </ol>
 *
 * <p>Refresh token persistence inherits Phase 1.3 behavior:
 * {@link RefreshTokenRecord} is saved inside the same tx so token
 * signing failure rolls back DB writes (no orphan account).
 */
@Service
public class UnifiedPhoneSmsAuthUseCase {

    static final String RATE_LIMIT_KEY_PREFIX = "auth:";
    static final Duration TIMING_TARGET = Duration.ofMillis(400);
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private final RateLimitService rateLimitService;
    private final SmsCodeService smsCodeService;
    private final AccountRepository accountRepository;
    private final CredentialRepository credentialRepository;
    private final TokenIssuer tokenIssuer;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TransactionTemplate transactionTemplate;
    private final Bandwidth authBandwidth;

    public UnifiedPhoneSmsAuthUseCase(
            RateLimitService rateLimitService,
            SmsCodeService smsCodeService,
            AccountRepository accountRepository,
            CredentialRepository credentialRepository,
            TokenIssuer tokenIssuer,
            RefreshTokenRepository refreshTokenRepository,
            TransactionTemplate transactionTemplate,
            AuthRateLimitProperties rateLimitProperties) {
        this.rateLimitService = rateLimitService;
        this.smsCodeService = smsCodeService;
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.tokenIssuer = tokenIssuer;
        this.refreshTokenRepository = refreshTokenRepository;
        this.transactionTemplate = transactionTemplate;
        this.authBandwidth = Bandwidth.builder()
                .capacity(rateLimitProperties.capacity())
                .refillIntervally(rateLimitProperties.capacity(), rateLimitProperties.period())
                .build();
    }

    public PhoneSmsAuthResult execute(PhoneSmsAuthCommand cmd) {
        return TimingDefenseExecutor.executeInConstantTime(TIMING_TARGET, () -> doExecute(cmd));
    }

    private PhoneSmsAuthResult doExecute(PhoneSmsAuthCommand cmd) {
        PhoneNumber phone = PhonePolicy.validate(cmd.phone());

        rateLimitService.consumeOrThrow(RATE_LIMIT_KEY_PREFIX + phone.e164(), authBandwidth);

        AttemptOutcome verifyResult = smsCodeService.verify(phone.e164(), cmd.code());
        if (!verifyResult.success()) {
            throw new InvalidCredentialsException();
        }

        Optional<Account> existing = accountRepository.findByPhone(phone);
        if (existing.isPresent()) {
            Account account = existing.get();
            if (!AccountStateMachine.canLogin(account)) {
                // FROZEN / ANONYMIZED — anti-enumeration: same byte shape as 码错
                throw new InvalidCredentialsException();
            }
            return transactionTemplate.execute(status -> persistLogin(account));
        }

        // Register branch: phone not in DB → auto-create
        try {
            return transactionTemplate.execute(status -> persistNewAccount(phone));
        } catch (DataIntegrityViolationException ex) {
            // Concurrent same-phone race (per spec CL-004): another
            // request created the account between our findByPhone and
            // INSERT. Fallthrough to login branch.
            Account fallback = accountRepository.findByPhone(phone).orElseThrow(InvalidCredentialsException::new);
            if (!AccountStateMachine.canLogin(fallback)) {
                throw new InvalidCredentialsException();
            }
            return transactionTemplate.execute(status -> persistLogin(fallback));
        }
    }

    private PhoneSmsAuthResult persistLogin(Account account) {
        Instant now = Instant.now();

        accountRepository.updateLastLoginAt(account.id(), now);

        String access = tokenIssuer.signAccess(account.id());
        String refresh = tokenIssuer.signRefresh();

        refreshTokenRepository.save(RefreshTokenRecord.createActive(
                RefreshTokenHasher.hash(refresh), account.id(), now.plus(REFRESH_TOKEN_TTL), now));

        return new PhoneSmsAuthResult(account.id().value(), access, refresh);
    }

    private PhoneSmsAuthResult persistNewAccount(PhoneNumber phone) {
        Instant now = Instant.now();

        Account account = new Account(phone, now);
        AccountStateMachine.activate(account, now);
        Account saved = accountRepository.save(account);
        AccountId id = saved.id();

        credentialRepository.save(new PhoneCredential(id, phone, now));

        // Unified auth semantics: register + immediate login → set
        // lastLoginAt now (per spec FR-005, client cannot distinguish
        // register vs login from response shape; lastLoginAt should be
        // populated on both paths).
        accountRepository.updateLastLoginAt(id, now);

        String access = tokenIssuer.signAccess(id);
        String refresh = tokenIssuer.signRefresh();

        refreshTokenRepository.save(RefreshTokenRecord.createActive(
                RefreshTokenHasher.hash(refresh), id, now.plus(REFRESH_TOKEN_TTL), now));

        return new PhoneSmsAuthResult(id.value(), access, refresh);
    }
}
