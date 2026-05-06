package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.MbwApplication;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountSmsCode;
import com.mbw.account.domain.model.AccountSmsCodePurpose;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.AccountSmsCodeRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.RefreshTokenHasher;
import com.mbw.account.infrastructure.scheduling.FrozenAccountAnonymizationScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end IT for the anonymize-frozen-accounts scheduler (per
 * {@code spec/account/anonymize-frozen-accounts/spec.md} T8).
 *
 * <p>Boots the full {@link MbwApplication} context against real
 * Testcontainers (PG + Redis) and exercises
 * {@link FrozenAccountAnonymizationScheduler} on a heterogeneous data
 * set: 10 FROZEN-with-elapsed-grace rows must transition to
 * ANONYMIZED, 5 ACTIVE rows stay untouched, 3 already-ANONYMIZED rows
 * stay untouched (the partial query filters them out).
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>10 transitioned rows: status=ANONYMIZED, phone NULL,
 *       previous_phone_hash non-null + 64 hex chars, display_name =
 *       "已注销用户", freeze_until NULL
 *   <li>Per-account refresh_token rows revoked
 *   <li>Per-account sms_code rows deleted
 *   <li>5 ACTIVE + 3 already-ANONYMIZED rows unchanged
 *   <li>Micrometer counters: scanned=10, succeeded=10, failures=0
 * </ul>
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class FrozenAccountAnonymizationE2EIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.schemas", () -> "account");
        registry.add("spring.flyway.default-schema", () -> "account");
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.modulith.events.jdbc-schema-initialization.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("mbw.auth.jwt.secret", () -> "test-secret-32-bytes-or-more-of-test-entropy-please-do-not-use");
    }

    @Autowired
    private FrozenAccountAnonymizationScheduler scheduler;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountSmsCodeRepository smsCodeRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void scheduler_should_anonymize_only_FROZEN_with_elapsed_grace_leaving_other_states_untouched() {
        Instant now = Instant.now();
        List<AccountId> shouldAnonymize = new ArrayList<>();
        List<AccountId> shouldStayActive = new ArrayList<>();
        List<AccountId> alreadyAnonymized = new ArrayList<>();

        // 10 FROZEN + freeze_until elapsed (eligible)
        for (int i = 0; i < 10; i++) {
            shouldAnonymize.add(seedFrozen(now.minusSeconds(60L * (i + 5)), now.minusSeconds(60L * (i + 1))));
        }
        // 5 ACTIVE (status filter rejects)
        for (int i = 0; i < 5; i++) {
            shouldStayActive.add(seedActive(now));
        }
        // 3 already ANONYMIZED (status filter rejects, partial index excludes)
        for (int i = 0; i < 3; i++) {
            alreadyAnonymized.add(seedAlreadyAnonymized(now));
        }

        // Capture scanned/succeeded counters before — the @Scheduled cron may
        // have ticked during boot, so we measure delta rather than absolute.
        double scannedBefore =
                meterRegistry.counter("account.anonymize.scanned").count();
        double succeededBefore =
                meterRegistry.counter("account.anonymize.succeeded").count();

        scheduler.anonymizeExpiredFrozenAccounts();

        double scannedAfter = meterRegistry.counter("account.anonymize.scanned").count();
        double succeededAfter =
                meterRegistry.counter("account.anonymize.succeeded").count();
        assertThat(scannedAfter - scannedBefore).isEqualTo(10.0);
        assertThat(succeededAfter - succeededBefore).isEqualTo(10.0);

        // The 10 anonymized: status flipped, phone cleared, hash set, sub-resources purged.
        for (AccountId id : shouldAnonymize) {
            Account reloaded = accountRepository.findById(id).orElseThrow();
            assertThat(reloaded.status()).isEqualTo(AccountStatus.ANONYMIZED);
            assertThat(reloaded.phone()).isNull();
            assertThat(reloaded.previousPhoneHash()).isNotNull().hasSize(64).matches("[0-9a-f]+");
            assertThat(reloaded.displayName()).isNotNull();
            assertThat(reloaded.displayName().value()).isEqualTo("已注销用户");
            assertThat(reloaded.freezeUntil()).isNull();

            assertThat(countActiveRefreshTokens(id))
                    .as("refresh_token rows revoked for accountId=%s", id.value())
                    .isZero();
            assertThat(countSmsCodeRows(id))
                    .as("sms_code rows deleted for accountId=%s", id.value())
                    .isZero();
        }

        // The 5 ACTIVE rows: still ACTIVE, phone present.
        for (AccountId id : shouldStayActive) {
            Account reloaded = accountRepository.findById(id).orElseThrow();
            assertThat(reloaded.status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(reloaded.phone()).isNotNull();
            assertThat(reloaded.previousPhoneHash()).isNull();
        }

        // The 3 already-ANONYMIZED rows: untouched.
        for (AccountId id : alreadyAnonymized) {
            Account reloaded = accountRepository.findById(id).orElseThrow();
            assertThat(reloaded.status()).isEqualTo(AccountStatus.ANONYMIZED);
            assertThat(reloaded.phone()).isNull();
        }
    }

    private AccountId seedFrozen(Instant createdAt, Instant freezeUntil) {
        return transactionTemplate.execute(status -> {
            PhoneNumber phone = new PhoneNumber(uniquePhone());
            Account account = AccountStateMachine.activate(new Account(phone, createdAt), createdAt);
            Account saved = accountRepository.save(account);
            AccountStateMachine.markFrozen(saved, freezeUntil, createdAt);
            accountRepository.save(saved);
            // Per-account sub-resources to verify the strategies clear them.
            refreshTokenRepository.save(RefreshTokenRecord.createActive(
                    RefreshTokenHasher.hash(UUID.randomUUID().toString()),
                    saved.id(),
                    createdAt.plusSeconds(30L * 24 * 3600),
                    createdAt));
            smsCodeRepository.save(AccountSmsCode.create(
                    saved.id(), "abcdef", createdAt.plusSeconds(300), AccountSmsCodePurpose.DELETE_ACCOUNT, createdAt));
            return saved.id();
        });
    }

    private AccountId seedActive(Instant now) {
        return transactionTemplate.execute(status -> {
            PhoneNumber phone = new PhoneNumber(uniquePhone());
            Account account = AccountStateMachine.activate(new Account(phone, now), now);
            return accountRepository.save(account).id();
        });
    }

    private AccountId seedAlreadyAnonymized(Instant now) {
        return transactionTemplate.execute(status -> {
            PhoneNumber phone = new PhoneNumber(uniquePhone());
            Instant past = now.minusSeconds(60L * 24 * 3600);
            Account account = AccountStateMachine.activate(new Account(phone, past), past);
            Account saved = accountRepository.save(account);
            AccountStateMachine.markFrozen(saved, past.plusSeconds(60), past);
            accountRepository.save(saved);
            AccountStateMachine.markAnonymizedFromFrozen(saved, past.plusSeconds(120), "deadbeef".repeat(8));
            accountRepository.save(saved);
            return saved.id();
        });
    }

    private Integer countActiveRefreshTokens(AccountId accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.refresh_token WHERE account_id = ? AND revoked_at IS NULL",
                Integer.class,
                accountId.value());
    }

    private Integer countSmsCodeRows(AccountId accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.account_sms_code WHERE account_id = ?", Integer.class, accountId.value());
    }

    private static String uniquePhone() {
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits()) % 10_000_000_000L;
        return "+8613" + String.format("%010d", suffix).substring(1);
    }
}
