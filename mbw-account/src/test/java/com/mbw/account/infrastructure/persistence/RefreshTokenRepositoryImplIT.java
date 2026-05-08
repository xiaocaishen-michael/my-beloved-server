package com.mbw.account.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mbw.account.api.dto.DeviceType;
import com.mbw.account.api.dto.LoginMethod;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.DeviceId;
import com.mbw.account.domain.model.DeviceName;
import com.mbw.account.domain.model.IpAddress;
import com.mbw.account.domain.model.RefreshTokenHash;
import com.mbw.account.domain.model.RefreshTokenPage;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.model.RefreshTokenRecordId;
import com.mbw.account.domain.repository.RefreshTokenRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers PG IT for {@link RefreshTokenRepositoryImpl}
 * (Phase 1.3 T5).
 *
 * <p>Mirror of {@link AccountRepositoryImplIT}'s structure — boots a
 * real PG, lets Flyway apply V1-V5, exercises the production
 * repository code (no mocks).
 */
@SpringBootTest(classes = RefreshTokenRepositoryImplIT.TestApp.class)
@Testcontainers
class RefreshTokenRepositoryImplIT {

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
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenJpaRepository refreshTokenJpaRepository;

    @AfterEach
    void cleanup() {
        refreshTokenJpaRepository.deleteAll();
    }

    @Test
    void save_should_assign_id_and_round_trip_via_findByTokenHash() {
        RefreshTokenHash hash = uniqueHash();
        AccountId accountId = new AccountId(1L);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // PG TIMESTAMPTZ stores µs precision
        Instant expiresAt = now.plusSeconds(30L * 24 * 3600);

        RefreshTokenRecord saved =
                refreshTokenRepository.save(RefreshTokenRecord.createActive(hash, accountId, expiresAt, now));

        assertThat(saved.id()).isNotNull();
        assertThat(saved.id().value()).isPositive();
        assertThat(saved.tokenHash()).isEqualTo(hash);
        assertThat(saved.accountId()).isEqualTo(accountId);
        assertThat(saved.revokedAt()).isNull();

        Optional<RefreshTokenRecord> found = refreshTokenRepository.findByTokenHash(hash);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(saved.id());
    }

    @Test
    void findByTokenHash_should_return_empty_when_not_found() {
        assertThat(refreshTokenRepository.findByTokenHash(uniqueHash())).isEmpty();
    }

    @Test
    void duplicate_token_hash_should_raise_DataIntegrityViolation() {
        RefreshTokenHash hash = uniqueHash();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // PG TIMESTAMPTZ stores µs precision
        Instant expiresAt = now.plusSeconds(3600);
        refreshTokenRepository.save(RefreshTokenRecord.createActive(hash, new AccountId(1L), expiresAt, now));

        assertThatThrownBy(() -> refreshTokenRepository.save(
                        RefreshTokenRecord.createActive(hash, new AccountId(2L), expiresAt, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revoke_should_set_revokedAt_on_active_record() {
        RefreshTokenHash hash = uniqueHash();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // PG TIMESTAMPTZ stores µs precision
        RefreshTokenRecord saved = refreshTokenRepository.save(
                RefreshTokenRecord.createActive(hash, new AccountId(1L), now.plusSeconds(3600), now));

        Instant revokedAt = now.plusSeconds(60);
        refreshTokenRepository.revoke(saved.id(), revokedAt);

        RefreshTokenRecord reloaded =
                refreshTokenRepository.findByTokenHash(hash).orElseThrow();
        assertThat(reloaded.revokedAt()).isEqualTo(revokedAt);
        assertThat(reloaded.isActive(now.plusSeconds(120))).isFalse();
    }

    @Test
    void revoke_should_be_noop_when_already_revoked() {
        RefreshTokenHash hash = uniqueHash();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // PG TIMESTAMPTZ stores µs precision
        RefreshTokenRecord saved = refreshTokenRepository.save(
                RefreshTokenRecord.createActive(hash, new AccountId(1L), now.plusSeconds(3600), now));

        Instant firstRevokedAt = now.plusSeconds(60);
        refreshTokenRepository.revoke(saved.id(), firstRevokedAt);

        // Second revoke with a different timestamp must NOT overwrite
        Instant secondRevokedAt = now.plusSeconds(120);
        refreshTokenRepository.revoke(saved.id(), secondRevokedAt);

        RefreshTokenRecord reloaded =
                refreshTokenRepository.findByTokenHash(hash).orElseThrow();
        assertThat(reloaded.revokedAt())
                .as("first revoke wins; second is a no-op")
                .isEqualTo(firstRevokedAt);
    }

    @Test
    void revokeAllForAccount_should_revoke_only_active_records() {
        AccountId target = new AccountId(7L);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // PG TIMESTAMPTZ stores µs precision
        Instant expiresAt = now.plusSeconds(3600);

        RefreshTokenRecord r1 =
                refreshTokenRepository.save(RefreshTokenRecord.createActive(uniqueHash(), target, expiresAt, now));
        RefreshTokenRecord r2 =
                refreshTokenRepository.save(RefreshTokenRecord.createActive(uniqueHash(), target, expiresAt, now));
        // Pre-revoked record (would be a no-op)
        RefreshTokenRecord r3 =
                refreshTokenRepository.save(RefreshTokenRecord.createActive(uniqueHash(), target, expiresAt, now));
        Instant earlyRevoke = now.plusSeconds(10);
        refreshTokenRepository.revoke(r3.id(), earlyRevoke);

        // Different account — must NOT be revoked
        RefreshTokenRecord otherAccount = refreshTokenRepository.save(
                RefreshTokenRecord.createActive(uniqueHash(), new AccountId(99L), expiresAt, now));

        Instant bulkRevokedAt = now.plusSeconds(60);
        int affected = refreshTokenRepository.revokeAllForAccount(target, bulkRevokedAt);

        assertThat(affected)
                .as("two active records of target account should be revoked")
                .isEqualTo(2);
        assertThat(refreshTokenRepository
                        .findByTokenHash(r1.tokenHash())
                        .orElseThrow()
                        .revokedAt())
                .isEqualTo(bulkRevokedAt);
        assertThat(refreshTokenRepository
                        .findByTokenHash(r2.tokenHash())
                        .orElseThrow()
                        .revokedAt())
                .isEqualTo(bulkRevokedAt);
        assertThat(refreshTokenRepository
                        .findByTokenHash(r3.tokenHash())
                        .orElseThrow()
                        .revokedAt())
                .as("pre-revoked record's revokedAt unchanged")
                .isEqualTo(earlyRevoke);
        assertThat(refreshTokenRepository
                        .findByTokenHash(otherAccount.tokenHash())
                        .orElseThrow()
                        .revokedAt())
                .as("different account's record untouched")
                .isNull();
    }

    @Test
    void revokeAllForAccount_should_return_zero_when_no_active_records() {
        int affected = refreshTokenRepository.revokeAllForAccount(new AccountId(404L), Instant.now());

        assertThat(affected).isZero();
    }

    // ----- Device-management spec T5 — V11 device columns + findById + findActiveByAccountId -----

    @Test
    void should_persist_and_load_five_device_fields() {
        RefreshTokenHash hash = uniqueHash();
        AccountId accountId = new AccountId(11L);
        DeviceId deviceId = new DeviceId("8a7c1f2e-5b3d-4f6a-9e2c-1d4b5a6c7e8f");
        DeviceName deviceName = new DeviceName("MK-iPhone");
        IpAddress ipAddress = new IpAddress("8.8.8.8");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = now.plusSeconds(3600);

        RefreshTokenRecord saved = refreshTokenRepository.save(RefreshTokenRecord.createActive(
                hash,
                accountId,
                deviceId,
                deviceName,
                DeviceType.PHONE,
                ipAddress,
                LoginMethod.PHONE_SMS,
                expiresAt,
                now));

        RefreshTokenRecord reloaded =
                refreshTokenRepository.findByTokenHash(hash).orElseThrow();
        assertThat(reloaded.deviceId()).isEqualTo(deviceId);
        assertThat(reloaded.deviceName()).isEqualTo(deviceName);
        assertThat(reloaded.deviceType()).isEqualTo(DeviceType.PHONE);
        assertThat(reloaded.ipAddress()).isEqualTo(ipAddress);
        assertThat(reloaded.loginMethod()).isEqualTo(LoginMethod.PHONE_SMS);
        assertThat(reloaded.id()).isEqualTo(saved.id());
    }

    @Test
    void should_handle_null_deviceName_and_ipAddress_round_trip() {
        RefreshTokenHash hash = uniqueHash();
        DeviceId deviceId = new DeviceId("aaaa1111-bbbb-2222-cccc-333344445555");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = now.plusSeconds(3600);

        refreshTokenRepository.save(RefreshTokenRecord.createActive(
                hash,
                new AccountId(12L),
                deviceId, /* deviceName */
                null,
                DeviceType.UNKNOWN,
                /* ipAddress */ null,
                LoginMethod.PHONE_SMS,
                expiresAt,
                now));

        RefreshTokenRecord reloaded =
                refreshTokenRepository.findByTokenHash(hash).orElseThrow();
        assertThat(reloaded.deviceName()).isNull();
        assertThat(reloaded.ipAddress()).isNull();
        assertThat(reloaded.deviceId()).isEqualTo(deviceId);
    }

    @Test
    void findById_should_return_record_when_present() {
        RefreshTokenHash hash = uniqueHash();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        RefreshTokenRecord saved = refreshTokenRepository.save(
                RefreshTokenRecord.createActive(hash, new AccountId(13L), now.plusSeconds(3600), now));

        Optional<RefreshTokenRecord> found = refreshTokenRepository.findById(saved.id());

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(saved.id());
        assertThat(found.get().tokenHash()).isEqualTo(hash);
    }

    @Test
    void findById_should_return_empty_when_id_missing() {
        assertThat(refreshTokenRepository.findById(new RefreshTokenRecordId(999_999L)))
                .isEmpty();
    }

    @Test
    void findActiveByAccountId_should_return_paginated_active_only_sorted_DESC() {
        AccountId target = new AccountId(21L);
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Three active rows at distinct created_at instants.
        RefreshTokenRecord r1 = refreshTokenRepository.save(
                RefreshTokenRecord.createActive(uniqueHash(), target, base.plusSeconds(3600), base));
        RefreshTokenRecord r2 = refreshTokenRepository.save(
                RefreshTokenRecord.createActive(uniqueHash(), target, base.plusSeconds(3600), base.plusSeconds(10)));
        RefreshTokenRecord r3 = refreshTokenRepository.save(
                RefreshTokenRecord.createActive(uniqueHash(), target, base.plusSeconds(3600), base.plusSeconds(20)));
        // Different account — must not appear.
        refreshTokenRepository.save(
                RefreshTokenRecord.createActive(uniqueHash(), new AccountId(22L), base.plusSeconds(3600), base));

        RefreshTokenPage page = refreshTokenRepository.findActiveByAccountId(target, 0, 10);

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.items()).hasSize(3);
        // Sorted by created_at DESC → r3, r2, r1.
        assertThat(page.items().get(0).id()).isEqualTo(r3.id());
        assertThat(page.items().get(1).id()).isEqualTo(r2.id());
        assertThat(page.items().get(2).id()).isEqualTo(r1.id());
    }

    @Test
    void findActiveByAccountId_should_exclude_revoked_rows() {
        AccountId target = new AccountId(31L);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        RefreshTokenRecord active = refreshTokenRepository.save(
                RefreshTokenRecord.createActive(uniqueHash(), target, now.plusSeconds(3600), now));
        RefreshTokenRecord revoked = refreshTokenRepository.save(
                RefreshTokenRecord.createActive(uniqueHash(), target, now.plusSeconds(3600), now.plusSeconds(5)));
        refreshTokenRepository.revoke(revoked.id(), now.plusSeconds(10));

        RefreshTokenPage page = refreshTokenRepository.findActiveByAccountId(target, 0, 10);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).id()).isEqualTo(active.id());
    }

    @Test
    void findActiveByAccountId_should_paginate_correctly() {
        AccountId target = new AccountId(41L);
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // 12 active rows for the same account, distinct timestamps for stable ordering.
        for (int i = 0; i < 12; i++) {
            refreshTokenRepository.save(
                    RefreshTokenRecord.createActive(uniqueHash(), target, base.plusSeconds(3600), base.plusSeconds(i)));
        }

        RefreshTokenPage page0 = refreshTokenRepository.findActiveByAccountId(target, 0, 10);
        RefreshTokenPage page1 = refreshTokenRepository.findActiveByAccountId(target, 1, 10);

        assertThat(page0.totalElements()).isEqualTo(12);
        assertThat(page0.items()).hasSize(10);
        assertThat(page1.totalElements()).isEqualTo(12);
        assertThat(page1.items()).hasSize(2);
    }

    @Test
    void findActiveByAccountId_should_return_empty_page_when_account_unknown() {
        RefreshTokenPage page = refreshTokenRepository.findActiveByAccountId(new AccountId(404L), 0, 10);

        assertThat(page.totalElements()).isZero();
        assertThat(page.items()).isEmpty();
    }

    /** Generates a unique 64-char lowercase hex hash for test isolation. */
    private static RefreshTokenHash uniqueHash() {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        // 32 hex chars per UUID (without dashes); concat two for 64
        String hex = uuid.toString().replace("-", "")
                + java.util.UUID.randomUUID().toString().replace("-", "");
        return new RefreshTokenHash(hex.toLowerCase());
    }

    /**
     * Minimal Spring Boot context — JPA-only, mirror of
     * {@link AccountRepositoryImplIT.TestApp}.
     */
    @Configuration
    @EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = {RefreshTokenJpaRepository.class})
    @EntityScan(basePackageClasses = {RefreshTokenJpaEntity.class})
    static class TestApp {

        @Bean
        RefreshTokenRepositoryImpl refreshTokenRepositoryImpl(RefreshTokenJpaRepository jpa) {
            return new RefreshTokenRepositoryImpl(jpa);
        }
    }
}
