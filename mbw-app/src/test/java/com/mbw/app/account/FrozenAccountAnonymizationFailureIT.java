package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

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
import com.mbw.account.infrastructure.scheduling.RefreshTokenAnonymizeStrategy;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
 * Failure-isolation IT for the anonymize-frozen-accounts scheduler (per
 * {@code spec/account/anonymize-frozen-accounts/tasks.md} T10).
 *
 * <p>Verifies that a RuntimeException thrown by one {@code AnonymizeStrategy}
 * mid-batch rolls back only that row's REQUIRES_NEW transaction while all
 * other rows succeed, and that a subsequent scheduler tick retries the
 * failed row successfully.
 *
 * <p>{@link RefreshTokenAnonymizeStrategy} is replaced by a {@link MockBean}
 * configured to throw on the 5th invocation. The 5th account (by
 * {@code freeze_until ASC}, the order the scheduler uses) stays FROZEN
 * with its sub-resources intact (TX rollback evidence); the other 9 are
 * ANONYMIZED.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class FrozenAccountAnonymizationFailureIT {

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

    @MockBean
    private RefreshTokenAnonymizeStrategy refreshTokenStrategy;

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
    void single_row_failure_does_not_stop_batch_and_retries_on_next_tick() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Seed 10 FROZEN accounts with freeze_until ordered ascending so the
        // scheduler (ORDER BY freeze_until ASC) processes them in a known order.
        // Account at index 4 (5th, freeze_until = base - 6s) will fail.
        List<AccountId> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            // base - 10s, base - 9s, ..., base - 1s (ascending)
            Instant freezeUntil = base.minusSeconds(10L - i);
            ids.add(seedFrozen(freezeUntil, base));
        }

        AccountId failingId = ids.get(4); // 5th in freeze_until ASC order

        // Configure: throw on the 5th call to refreshTokenStrategy.apply.
        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(inv -> {
                    if (callCount.incrementAndGet() == 5) {
                        throw new RuntimeException("simulated strategy failure on row 5");
                    }
                    return null;
                })
                .when(refreshTokenStrategy)
                .apply(any(), any());

        // ── Run 1: 9 succeed, 1 fails ────────────────────────────────────────────

        double scannedBefore =
                meterRegistry.counter("account.anonymize.scanned").count();
        double succeededBefore =
                meterRegistry.counter("account.anonymize.succeeded").count();

        scheduler.anonymizeExpiredFrozenAccounts();

        double scannedAfter = meterRegistry.counter("account.anonymize.scanned").count();
        double succeededAfter =
                meterRegistry.counter("account.anonymize.succeeded").count();

        assertThat(scannedAfter - scannedBefore).isEqualTo(10.0);
        assertThat(succeededAfter - succeededBefore).isEqualTo(9.0);
        assertThat(meterRegistry
                        .counter("account.anonymize.failures", "reason", "RuntimeException")
                        .count())
                .as("failures counter for the single failed row")
                .isEqualTo(1.0);
        assertThat(meterRegistry
                        .counter("account.anonymize.persistent_failures")
                        .count())
                .as("no persistent_failures: count (1) is below threshold (3)")
                .isZero();

        // 9 rows must be ANONYMIZED.
        for (int i = 0; i < 10; i++) {
            Account a = accountRepository.findById(ids.get(i)).orElseThrow();
            if (i == 4) {
                assertThat(a.status())
                        .as("row 5 must remain FROZEN (TX rolled back)")
                        .isEqualTo(AccountStatus.FROZEN);
                assertThat(a.phone())
                        .as("row 5 phone must be intact (rollback)")
                        .isNotNull();
            } else {
                assertThat(a.status()).as("row %d must be ANONYMIZED", i + 1).isEqualTo(AccountStatus.ANONYMIZED);
            }
        }

        // Row 5 sub-resources: TX rolled back, so refresh_token still active
        // and sms_code row still present.
        assertThat(countActiveRefreshTokens(failingId))
                .as("refresh_token still active after TX rollback for row 5")
                .isEqualTo(1);
        assertThat(countSmsCodeRows(failingId))
                .as("sms_code row still present after TX rollback for row 5")
                .isEqualTo(1);

        // Row 5 event_publication: no AccountAnonymizedEvent committed.
        Integer failingEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.event_publication"
                        + " WHERE event_type LIKE '%AccountAnonymizedEvent%'"
                        + " AND serialized_event LIKE ?",
                Integer.class, "%" + failingId.value() + "%");
        assertThat(failingEvents)
                .as("no AccountAnonymizedEvent in outbox for failed row 5")
                .isZero();

        // ── Run 2: mock reset → row 5 is retried and succeeds ───────────────────

        reset(refreshTokenStrategy); // restore mock to no-op

        double succeeded2Before =
                meterRegistry.counter("account.anonymize.succeeded").count();
        scheduler.anonymizeExpiredFrozenAccounts();
        double succeeded2After =
                meterRegistry.counter("account.anonymize.succeeded").count();

        assertThat(succeeded2After - succeeded2Before)
                .as("row 5 succeeds on retry")
                .isEqualTo(1.0);

        Account retried = accountRepository.findById(failingId).orElseThrow();
        assertThat(retried.status())
                .as("row 5 is ANONYMIZED after successful retry")
                .isEqualTo(AccountStatus.ANONYMIZED);
        assertThat(retried.phone()).as("phone cleared on retry").isNull();
    }

    private AccountId seedFrozen(Instant freezeUntil, Instant createdAt) {
        AccountId[] holder = new AccountId[1];
        transactionTemplate.execute(st -> {
            PhoneNumber pn = new PhoneNumber(uniquePhone());
            Account account = new Account(pn, createdAt);
            AccountStateMachine.activate(account, createdAt);
            AccountStateMachine.markFrozen(account, freezeUntil, createdAt);
            Account saved = accountRepository.save(account);
            refreshTokenRepository.save(RefreshTokenRecord.createActive(
                    RefreshTokenHasher.hash(UUID.randomUUID().toString()),
                    saved.id(),
                    createdAt.plusSeconds(30L * 24 * 3600),
                    createdAt));
            smsCodeRepository.save(AccountSmsCode.create(
                    saved.id(),
                    "abcdef123456789",
                    createdAt.plusSeconds(300),
                    AccountSmsCodePurpose.DELETE_ACCOUNT,
                    createdAt));
            holder[0] = saved.id();
            return null;
        });
        return holder[0];
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
