package com.mbw.app.infrastructure.sms;

import com.mbw.account.domain.model.PasswordHash;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.VerificationCodeRepository;
import com.mbw.account.domain.service.PasswordHasher;
import com.mbw.shared.api.sms.AttemptOutcome;
import com.mbw.shared.api.sms.SmsCodeService;
import com.mbw.shared.web.RateLimitedException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Redis-backed implementation of {@link SmsCodeService} (T2b).
 *
 * <p>Composes the lower-level {@link VerificationCodeRepository}
 * (Lua-atomic Redis ops, mbw-account/infrastructure/persistence) with
 * BCrypt hashing via {@link PasswordHasher}, presenting the
 * cross-module {@link SmsCodeService} contract per
 * {@code spec/account/register-by-phone/plan.md} § "SmsCodeService 跨
 * 模块归属".
 *
 * <p>{@link #generateAndStore} returns plaintext so the caller can
 * forward it through {@code SmsClient}; the hash is what lands in
 * Redis (5 minute TTL per FR-002). {@link #verify} uses BCrypt's
 * constant-time match; on mismatch the underlying Lua script
 * atomically increments the failed-attempt counter and (at the
 * configured threshold) deletes the entry.
 */
@Component
public class RedisSmsCodeService implements SmsCodeService {

    private static final Logger LOG = LoggerFactory.getLogger(RedisSmsCodeService.class);

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 3;
    private static final int CODE_DIGITS = 6;
    private static final int CODE_BOUND = 1_000_000;
    private static final String FIXED_CODE_PATTERN = "\\d{" + CODE_DIGITS + "}";

    private final VerificationCodeRepository repository;
    private final PasswordHasher passwordHasher;
    private final SecureRandom random;
    private final String devFixedCode;

    @Autowired
    public RedisSmsCodeService(
            VerificationCodeRepository repository,
            PasswordHasher passwordHasher,
            @Value("${mbw.sms.dev-fixed-code:}") String devFixedCode) {
        this(repository, passwordHasher, new SecureRandom(), devFixedCode);
    }

    // Backwards-compat constructor for unit tests that don't exercise dev-fixed-code branch.
    RedisSmsCodeService(VerificationCodeRepository repository, PasswordHasher passwordHasher, SecureRandom random) {
        this(repository, passwordHasher, random, null);
    }

    RedisSmsCodeService(
            VerificationCodeRepository repository,
            PasswordHasher passwordHasher,
            SecureRandom random,
            String devFixedCode) {
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.random = random;
        this.devFixedCode = devFixedCode;
    }

    @Override
    public String generateAndStore(String phone) {
        PhoneNumber phoneNumber = new PhoneNumber(phone);
        String plaintext;
        if (devFixedCode != null && devFixedCode.matches(FIXED_CODE_PATTERN)) {
            // Dev/test escape hatch: skip random generation, use the env-provided
            // fixed code (e.g. 999999) so the user can input it directly without
            // injecting a magic bcrypt hash into Redis. Prod must NOT set
            // MBW_SMS_DEV_FIXED_CODE — the env var is undefined in
            // docker-compose.tight.yml so this branch never fires under prod.
            LOG.warn("[dev-fixed-code] using fixed SMS code from env (NOT for prod) phone={}", phone);
            plaintext = devFixedCode;
        } else {
            plaintext = String.format(Locale.ROOT, "%0" + CODE_DIGITS + "d", random.nextInt(CODE_BOUND));
        }
        PasswordHash hash = passwordHasher.hash(plaintext);
        boolean stored = repository.storeIfAbsent(phoneNumber, hash.value(), CODE_TTL);
        if (!stored) {
            // FR-006 60s rate limit upstream usually catches duplicate sends, but
            // race conditions (multi-tab clicks, retried requests) can still arrive
            // here. Surface as RateLimitedException → HTTP 429 so the user sees
            // "请稍后再试" instead of a generic 500. CODE_TTL is the conservative
            // upper bound on how long the existing code stays valid.
            throw new RateLimitedException("sms-code-pending:" + phone, CODE_TTL);
        }
        return plaintext;
    }

    @Override
    public AttemptOutcome verify(String phone, String code) {
        PhoneNumber phoneNumber = new PhoneNumber(phone);
        Optional<String> storedHash = repository.findHashByPhone(phoneNumber);
        if (storedHash.isEmpty()) {
            // No active code — treat as already-invalidated for FR-007
            // anti-enumeration; the use case maps to InvalidCredentials
            return new AttemptOutcome(false, 0, true);
        }

        if (passwordHasher.matches(code, new PasswordHash(storedHash.get()))) {
            repository.delete(phoneNumber);
            return new AttemptOutcome(true, 0, false);
        }

        VerificationCodeRepository.AttemptOutcome bumped =
                repository.incrementAttemptOrInvalidate(phoneNumber, MAX_ATTEMPTS);
        return new AttemptOutcome(false, bumped.count(), bumped.invalidated());
    }
}
