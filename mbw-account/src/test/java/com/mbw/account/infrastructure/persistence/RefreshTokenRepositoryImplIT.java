package com.mbw.account.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.RefreshTokenHash;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import java.time.Instant;
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
        Instant now = Instant.now();
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
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(3600);
        refreshTokenRepository.save(RefreshTokenRecord.createActive(hash, new AccountId(1L), expiresAt, now));

        assertThatThrownBy(() -> refreshTokenRepository.save(
                        RefreshTokenRecord.createActive(hash, new AccountId(2L), expiresAt, now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revoke_should_set_revokedAt_on_active_record() {
        RefreshTokenHash hash = uniqueHash();
        Instant now = Instant.now();
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
        Instant now = Instant.now();
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
        Instant now = Instant.now();
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
