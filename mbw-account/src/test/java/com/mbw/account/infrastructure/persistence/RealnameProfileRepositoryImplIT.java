package com.mbw.account.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mbw.account.domain.model.FailedReason;
import com.mbw.account.domain.model.RealnameProfile;
import com.mbw.account.domain.model.RealnameStatus;
import com.mbw.account.domain.repository.RealnameProfileRepository;
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
 * Testcontainers PG IT for {@link RealnameProfileRepositoryImpl}
 * (realname-verification spec T7).
 *
 * <p>Boots a real {@code postgres:16-alpine}, applies V1-V11 via Flyway, and
 * exercises the production repository code (no mocks). Mirrors the structure
 * of {@code AccountRepositoryImplIT}; lives in {@code mbw-account} (not
 * {@code mbw-app} as the original tasks.md draft suggested) for consistency
 * with the rest of the module's persistence ITs.
 */
@SpringBootTest(classes = RealnameProfileRepositoryImplIT.TestApp.class)
@Testcontainers
class RealnameProfileRepositoryImplIT {

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
    private RealnameProfileRepository repository;

    @Autowired
    private RealnameProfileJpaRepository jpa;

    @AfterEach
    void cleanup() {
        jpa.deleteAll();
    }

    private static final byte[] REAL_NAME_ENC = "encrypted-real-name".getBytes();
    private static final byte[] ID_CARD_NO_ENC = "encrypted-id-card-no".getBytes();
    private static final String ID_CARD_HASH = "a".repeat(64);
    private static final String PROVIDER_BIZ_ID = "biz-001";

    @Test
    void save_then_findByAccountId_round_trips_all_fields() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // PG TIMESTAMPTZ stores µs
        long accountId = 1001L;
        RealnameProfile pending = RealnameProfile.unverified(accountId, now)
                .withPending(REAL_NAME_ENC, ID_CARD_NO_ENC, ID_CARD_HASH, PROVIDER_BIZ_ID, now.plusSeconds(60));

        RealnameProfile saved = repository.save(pending);

        assertThat(saved.id()).isNotNull().isPositive();

        Optional<RealnameProfile> found = repository.findByAccountId(accountId);
        assertThat(found).isPresent();
        RealnameProfile loaded = found.get();
        assertThat(loaded.id()).isEqualTo(saved.id());
        assertThat(loaded.accountId()).isEqualTo(accountId);
        assertThat(loaded.status()).isEqualTo(RealnameStatus.PENDING);
        assertThat(loaded.realNameEnc()).isEqualTo(REAL_NAME_ENC);
        assertThat(loaded.idCardNoEnc()).isEqualTo(ID_CARD_NO_ENC);
        assertThat(loaded.idCardHash()).isEqualTo(ID_CARD_HASH);
        assertThat(loaded.providerBizId()).isEqualTo(PROVIDER_BIZ_ID);
        assertThat(loaded.retryCount24h()).isZero();
        assertThat(loaded.createdAt()).isEqualTo(now);
        assertThat(loaded.updatedAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    void findByIdCardHash_returns_profile_when_hash_matches() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repository.save(RealnameProfile.unverified(2001L, now)
                .withPending(REAL_NAME_ENC, ID_CARD_NO_ENC, ID_CARD_HASH, "biz-A", now));

        Optional<RealnameProfile> found = repository.findByIdCardHash(ID_CARD_HASH);

        assertThat(found).isPresent();
        assertThat(found.get().accountId()).isEqualTo(2001L);
    }

    @Test
    void findByIdCardHash_returns_empty_when_hash_unknown() {
        Optional<RealnameProfile> found = repository.findByIdCardHash("z".repeat(64));
        assertThat(found).isEmpty();
    }

    @Test
    void findByProviderBizId_returns_profile_when_biz_id_matches() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String bizId = "biz-find-by-bizid";
        repository.save(RealnameProfile.unverified(3001L, now)
                .withPending(REAL_NAME_ENC, ID_CARD_NO_ENC, "b".repeat(64), bizId, now));

        Optional<RealnameProfile> found = repository.findByProviderBizId(bizId);

        assertThat(found).isPresent();
        assertThat(found.get().accountId()).isEqualTo(3001L);
    }

    @Test
    void save_same_account_twice_upserts_in_place() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long accountId = 4001L;
        RealnameProfile first = repository.save(RealnameProfile.unverified(accountId, now)
                .withPending(REAL_NAME_ENC, ID_CARD_NO_ENC, "c".repeat(64), "biz-first", now.plusSeconds(60)));

        RealnameProfile retransition = first.withFailed(FailedReason.NAME_ID_MISMATCH, now.plusSeconds(120));
        RealnameProfile second = repository.save(retransition);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jpa.count()).isEqualTo(1L);

        Optional<RealnameProfile> reloaded = repository.findByAccountId(accountId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().status()).isEqualTo(RealnameStatus.FAILED);
        assertThat(reloaded.get().failedReason()).isEqualTo(FailedReason.NAME_ID_MISMATCH);
        assertThat(reloaded.get().retryCount24h()).isEqualTo(1);
    }

    @Test
    void save_two_accounts_with_same_id_card_hash_raises_DataIntegrityViolation() {
        // FR-013 / SC-003 — partial unique uk_realname_profile_id_card_hash enforces
        // one ID card to one account at a time.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String sharedHash = "d".repeat(64);
        repository.save(RealnameProfile.unverified(5001L, now)
                .withPending(REAL_NAME_ENC, ID_CARD_NO_ENC, sharedHash, "biz-A", now));

        assertThatThrownBy(() -> repository.save(RealnameProfile.unverified(5002L, now)
                        .withPending(REAL_NAME_ENC, ID_CARD_NO_ENC, sharedHash, "biz-B", now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_two_unverified_rows_with_null_hash_succeeds_via_partial_index() {
        // Partial unique index has WHERE id_card_hash IS NOT NULL — multiple
        // UNVERIFIED rows (hash = null) must coexist freely.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repository.save(RealnameProfile.unverified(6001L, now));
        repository.save(RealnameProfile.unverified(6002L, now));

        assertThat(jpa.count()).isEqualTo(2L);
    }

    @Configuration
    @EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = {RealnameProfileJpaRepository.class})
    @EntityScan(basePackageClasses = {RealnameProfileJpaEntity.class})
    static class TestApp {

        @Bean
        RealnameProfileRepositoryImpl realnameProfileRepositoryImpl(RealnameProfileJpaRepository jpa) {
            return new RealnameProfileRepositoryImpl(jpa);
        }
    }
}
