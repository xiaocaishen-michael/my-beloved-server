package com.mbw.account.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountSmsCode;
import com.mbw.account.domain.model.AccountSmsCodeId;
import com.mbw.account.domain.model.AccountSmsCodePurpose;
import com.mbw.account.domain.repository.AccountSmsCodeRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers PG IT for {@link AccountSmsCodeRepositoryImpl}
 * (delete-account spec T4).
 *
 * <p>Boots a real PG, applies V1-V8 via Flyway, exercises production
 * repository code (no mocks). Mirror of
 * {@link RefreshTokenRepositoryImplIT}'s structure.
 */
@SpringBootTest(classes = AccountSmsCodeRepositoryImplIT.TestApp.class)
@Testcontainers
class AccountSmsCodeRepositoryImplIT {

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
    private AccountSmsCodeRepository smsCodeRepository;

    @Autowired
    private AccountSmsCodeJpaRepository smsCodeJpaRepository;

    @AfterEach
    void cleanup() {
        smsCodeJpaRepository.deleteAll();
    }

    @Test
    void should_findActiveByPurpose_return_active_record_when_DELETE_ACCOUNT_code_exists() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // PG TIMESTAMPTZ stores µs precision
        AccountId accountId = new AccountId(1L);
        AccountSmsCode saved = smsCodeRepository.save(AccountSmsCode.create(
                accountId, "aabbcc", now.plusSeconds(300), AccountSmsCodePurpose.DELETE_ACCOUNT, now));

        Optional<AccountSmsCode> found =
                smsCodeRepository.findActiveByPurposeAndAccountId(AccountSmsCodePurpose.DELETE_ACCOUNT, accountId, now);

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(saved.id());
        assertThat(found.get().codeHash()).isEqualTo("aabbcc");
        assertThat(found.get().purpose()).isEqualTo(AccountSmsCodePurpose.DELETE_ACCOUNT);
        assertThat(found.get().usedAt()).isNull();
    }

    @Test
    void should_findActiveByPurpose_return_empty_when_only_PHONE_SMS_AUTH_exists() {
        // purpose 物理隔离：DELETE_ACCOUNT query must not match PHONE_SMS_AUTH rows
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // PG TIMESTAMPTZ stores µs precision
        AccountId accountId = new AccountId(2L);
        smsCodeRepository.save(AccountSmsCode.create(
                accountId, "ddeeff", now.plusSeconds(300), AccountSmsCodePurpose.PHONE_SMS_AUTH, now));

        Optional<AccountSmsCode> found =
                smsCodeRepository.findActiveByPurposeAndAccountId(AccountSmsCodePurpose.DELETE_ACCOUNT, accountId, now);

        assertThat(found).isEmpty();
    }

    @Test
    void should_findActiveByPurpose_exclude_used_records() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // PG TIMESTAMPTZ stores µs precision
        AccountId accountId = new AccountId(3L);
        AccountSmsCode saved = smsCodeRepository.save(AccountSmsCode.create(
                accountId, "112233", now.plusSeconds(300), AccountSmsCodePurpose.DELETE_ACCOUNT, now));

        smsCodeRepository.markUsed(saved.id(), now.plusSeconds(10));

        Optional<AccountSmsCode> found = smsCodeRepository.findActiveByPurposeAndAccountId(
                AccountSmsCodePurpose.DELETE_ACCOUNT, accountId, now.plusSeconds(20));

        assertThat(found).isEmpty();
    }

    @Test
    void should_findActiveByPurpose_exclude_expired_records() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // PG TIMESTAMPTZ stores µs precision
        AccountId accountId = new AccountId(4L);
        // expires_at = now+1s; query with now+2s — should already be expired
        smsCodeRepository.save(AccountSmsCode.create(
                accountId, "445566", now.plusSeconds(1), AccountSmsCodePurpose.DELETE_ACCOUNT, now));

        Optional<AccountSmsCode> found = smsCodeRepository.findActiveByPurposeAndAccountId(
                AccountSmsCodePurpose.DELETE_ACCOUNT, accountId, now.plusSeconds(2));

        assertThat(found).isEmpty();
    }

    @Test
    void should_save_and_round_trip_purpose_field() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // PG TIMESTAMPTZ stores µs precision
        AccountId accountId = new AccountId(5L);
        AccountSmsCode saved = smsCodeRepository.save(AccountSmsCode.create(
                accountId, "778899", now.plusSeconds(300), AccountSmsCodePurpose.PHONE_SMS_AUTH, now));

        assertThat(saved.id()).isNotNull();
        assertThat(saved.id()).isInstanceOf(AccountSmsCodeId.class);
        assertThat(saved.id().value()).isPositive();
        assertThat(saved.purpose()).isEqualTo(AccountSmsCodePurpose.PHONE_SMS_AUTH);
        assertThat(saved.accountId()).isEqualTo(accountId);
        assertThat(saved.usedAt()).isNull();
    }

    /**
     * Minimal Spring Boot context — JPA-only, mirror of
     * {@link AccountRepositoryImplIT.TestApp}.
     */
    @Configuration
    @EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = {AccountSmsCodeJpaRepository.class})
    @EntityScan(basePackageClasses = {AccountSmsCodeJpaEntity.class})
    static class TestApp {

        @Bean
        AccountSmsCodeRepositoryImpl accountSmsCodeRepositoryImpl(AccountSmsCodeJpaRepository jpa) {
            return new AccountSmsCodeRepositoryImpl(jpa);
        }
    }
}
