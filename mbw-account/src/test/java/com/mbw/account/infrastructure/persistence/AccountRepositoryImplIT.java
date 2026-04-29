package com.mbw.account.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountStateMachine;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers PG IT for {@link AccountRepositoryImpl}.
 *
 * <p>Boots a real {@code postgres:16-alpine} container, lets Flyway
 * apply V1 + V2 migrations, then exercises the production repository
 * code (no mocks). Verifies:
 *
 * <ul>
 *   <li>save assigns the IDENTITY-generated id back to the in-memory
 *       Account aggregate
 *   <li>findByPhone / existsByPhone reflect persisted state
 *   <li><b>SC-003 concurrency:</b> 10 parallel saves of the same phone
 *       result in exactly 1 success + 9 {@code DataIntegrityViolation}
 *       (the V2 {@code uk_account_phone} index enforces FR-005)
 *   <li>UNIQUE constraint surfaces as Spring's
 *       {@link DataIntegrityViolationException}, ready for the use-case
 *       layer to map to {@code INVALID_CREDENTIALS} (FR-007)
 * </ul>
 */
@SpringBootTest(classes = AccountRepositoryImplIT.TestApp.class)
@Testcontainers
class AccountRepositoryImplIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void dataSourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/account");
        registry.add("spring.flyway.schemas", () -> "account");
        registry.add("spring.flyway.default-schema", () -> "account");
        registry.add("spring.flyway.create-schemas", () -> "true");
    }

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountJpaRepository accountJpaRepository;

    @Autowired
    private CredentialJpaRepository credentialJpaRepository;

    @AfterEach
    void cleanup() {
        credentialJpaRepository.deleteAll();
        accountJpaRepository.deleteAll();
    }

    @Test
    void save_should_assign_generated_id_back_to_in_memory_account() {
        Instant now = Instant.now();
        Account fresh = AccountStateMachine.activate(new Account(uniquePhone(), now), now);

        Account saved = accountRepository.save(fresh);

        assertThat(saved).isSameAs(fresh);
        assertThat(saved.id()).isNotNull();
        assertThat(saved.id().value()).isPositive();
    }

    @Test
    void findByPhone_should_return_saved_account() {
        Instant now = Instant.now();
        PhoneNumber phone = uniquePhone();
        Account fresh = AccountStateMachine.activate(new Account(phone, now), now);
        accountRepository.save(fresh);

        Optional<Account> found = accountRepository.findByPhone(phone);

        assertThat(found).isPresent();
        Account loaded = found.get();
        assertThat(loaded.phone()).isEqualTo(phone);
        assertThat(loaded.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(loaded.id().value()).isPositive();
    }

    @Test
    void existsByPhone_should_return_true_after_save_and_false_otherwise() {
        Instant now = Instant.now();
        PhoneNumber phone = uniquePhone();

        assertThat(accountRepository.existsByPhone(phone)).isFalse();

        Account fresh = AccountStateMachine.activate(new Account(phone, now), now);
        accountRepository.save(fresh);

        assertThat(accountRepository.existsByPhone(phone)).isTrue();
    }

    @Test
    void duplicate_phone_should_raise_DataIntegrityViolation() {
        Instant now = Instant.now();
        PhoneNumber phone = uniquePhone();

        accountRepository.save(AccountStateMachine.activate(new Account(phone, now), now));

        assertThatThrownBy(() -> accountRepository.save(AccountStateMachine.activate(new Account(phone, now), now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * SC-003: 10 parallel saves of the same phone must result in exactly
     * 1 success + 9 DataIntegrityViolation. The V2
     * {@code uk_account_phone} index is the ground-truth race-winner.
     */
    @Test
    void concurrent_same_phone_saves_should_serialize_to_exactly_one_winner() throws InterruptedException {
        PhoneNumber phone = uniquePhone();
        int threadCount = 10;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Instant now = Instant.now();
                    Account fresh = AccountStateMachine.activate(new Account(phone, now), now);
                    accountRepository.save(fresh);
                    successes.incrementAndGet();
                } catch (DataIntegrityViolationException e) {
                    conflicts.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finished).as("all threads completed within 20s").isTrue();
        assertThat(successes.get())
                .as("exactly one save wins under concurrency")
                .isEqualTo(1);
        assertThat(conflicts.get())
                .as("the other 9 threads fail with DataIntegrityViolation")
                .isEqualTo(9);
        assertThat(accountJpaRepository.count()).as("only one row persisted").isEqualTo(1L);
    }

    private static PhoneNumber uniquePhone() {
        // E.164 mainland: +861[3-9]\d{9}; randomize the last 10 digits to avoid
        // cross-test collisions while staying inside the regex.
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits()) % 10_000_000_000L;
        return new PhoneNumber("+8613" + String.format("%010d", suffix).substring(1));
    }

    /**
     * Minimal Spring Boot context — JPA-only. {@code @Configuration} +
     * {@link EnableAutoConfiguration} (rather than
     * {@code @SpringBootApplication}) skips the default
     * {@code @ComponentScan} of this class's package — which would
     * otherwise pull in {@code RedisVerificationCodeRepository} and
     * require Redis wiring we deliberately exclude here. The repository
     * impl is declared as an explicit {@code @Bean}; Flyway is
     * auto-configured from {@code spring.flyway.*} dynamic properties.
     */
    @Configuration
    @EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = {AccountJpaRepository.class})
    @EntityScan(basePackageClasses = {AccountJpaEntity.class})
    static class TestApp {

        @Bean
        AccountRepositoryImpl accountRepositoryImpl(AccountJpaRepository jpa) {
            return new AccountRepositoryImpl(jpa);
        }
    }
}
