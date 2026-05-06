package com.mbw.app.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.MbwApplication;
import com.mbw.account.application.command.AnonymizeFrozenAccountCommand;
import com.mbw.account.application.command.CancelDeletionCommand;
import com.mbw.account.application.usecase.AnonymizeFrozenAccountUseCase;
import com.mbw.account.application.usecase.CancelDeletionUseCase;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
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
 * Concurrency IT for the anonymize-frozen-accounts scheduler vs the
 * cancel-deletion use case (per {@code spec/account/
 * anonymize-frozen-accounts/tasks.md} T9, SC-007 race safety).
 *
 * <p>One FROZEN account (freeze_until already elapsed) is attacked
 * concurrently by:
 *
 * <ul>
 *   <li>Thread A — {@link AnonymizeFrozenAccountUseCase}: acquires a
 *       PG pessimistic write lock, transitions FROZEN → ANONYMIZED.
 *   <li>Thread B — {@link CancelDeletionUseCase}: acquires the same
 *       PG write lock (or waits), then finds {@code freeze_until} is
 *       in the past and throws {@link InvalidCredentialsException}.
 * </ul>
 *
 * <p>Invariant: the account always lands in exactly one consistent
 * terminal state (ANONYMIZED) with no phantom outbox events for the
 * failed cancel path.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class FrozenAccountAnonymizationConcurrencyIT {

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
    private AnonymizeFrozenAccountUseCase anonymizeUseCase;

    @Autowired
    private CancelDeletionUseCase cancelDeletionUseCase;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void concurrent_anonymize_and_cancel_should_leave_account_in_consistent_terminal_state()
            throws InterruptedException {
        String phone = uniquePhone();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // freeze_until is 30 s in the past: anonymize-eligible, cancel-ineligible.
        Instant freezeUntil = now.minusSeconds(30);
        AccountId accountId = seedFrozen(phone, freezeUntil);

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> threadAErr = new AtomicReference<>();
        AtomicReference<Throwable> threadBErr = new AtomicReference<>();

        Thread threadA = new Thread(
                () -> {
                    try {
                        barrier.await();
                    } catch (InterruptedException | java.util.concurrent.BrokenBarrierException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // Any unchecked exception is captured by the UncaughtExceptionHandler below.
                    anonymizeUseCase.execute(new AnonymizeFrozenAccountCommand(accountId));
                },
                "thread-anonymize");
        threadA.setUncaughtExceptionHandler((t, err) -> threadAErr.set(err));

        Thread threadB = new Thread(
                () -> {
                    try {
                        barrier.await();
                    } catch (InterruptedException | java.util.concurrent.BrokenBarrierException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // Code "000000" never checked — eligibility fails first; captured below.
                    cancelDeletionUseCase.execute(new CancelDeletionCommand(phone, "000000", uniqueIp()));
                },
                "thread-cancel");
        threadB.setUncaughtExceptionHandler((t, err) -> threadBErr.set(err));

        threadA.start();
        threadB.start();
        threadA.join(10_000);
        threadB.join(10_000);

        // Core invariant: DB is in exactly one consistent terminal state.
        Account reloaded = accountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.status())
                .as("account must transition to ANONYMIZED (freeze_until is expired)")
                .isEqualTo(AccountStatus.ANONYMIZED);
        assertThat(reloaded.phone()).as("phone cleared on anonymize").isNull();

        // Thread A must succeed (freeze_until is past, FROZEN state → anonymize eligible).
        assertThat(threadAErr.get()).as("anonymize thread must not throw").isNull();

        // Thread B must be rejected (freeze_until is past → cancel ineligible → 401 domain).
        assertThat(threadBErr.get())
                .as("cancel thread must fail: freeze_until is expired")
                .isInstanceOf(InvalidCredentialsException.class);

        // No AccountDeletionCancelledEvent must appear in the outbox for this account:
        // cancel never reached the commit path.
        Integer cancelEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public.event_publication"
                        + " WHERE event_type LIKE '%AccountDeletionCancelledEvent%'"
                        + " AND serialized_event LIKE ?",
                Integer.class, "%" + accountId.value() + "%");
        assertThat(cancelEvents)
                .as("no AccountDeletionCancelledEvent in outbox for this account")
                .isZero();
    }

    private AccountId seedFrozen(String phone, Instant freezeUntil) {
        AccountId[] holder = new AccountId[1];
        transactionTemplate.executeWithoutResult(st -> {
            Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
            PhoneNumber pn = new PhoneNumber(phone);
            Account account = new Account(pn, createdAt);
            AccountStateMachine.activate(account, createdAt);
            AccountStateMachine.markFrozen(account, freezeUntil, createdAt);
            holder[0] = accountRepository.save(account).id();
        });
        return holder[0];
    }

    private static String uniquePhone() {
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits()) % 10_000_000_000L;
        return "+8613" + String.format("%010d", suffix).substring(1);
    }

    private static String uniqueIp() {
        long t = System.nanoTime();
        int b1 = (int) ((t >>> 16) & 0xFF);
        int b2 = (int) ((t >>> 8) & 0xFF);
        int b3 = (int) (t & 0xFF);
        return "10." + b1 + "." + b2 + "." + b3;
    }
}
