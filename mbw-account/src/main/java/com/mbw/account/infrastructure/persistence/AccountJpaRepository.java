package com.mbw.account.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link AccountJpaEntity}.
 *
 * <p>Package-private interface — only {@link AccountRepositoryImpl}
 * inside the same package consumes it; the rest of the codebase
 * depends on the {@code AccountRepository} domain contract.
 */
interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, Long> {

    Optional<AccountJpaEntity> findByPhone(String phone);

    /**
     * Pessimistic-write variant: emits {@code SELECT … FOR UPDATE},
     * serialising concurrent callers on the row until the surrounding
     * transaction commits. Backs
     * {@code AccountRepository.findByPhoneForUpdate} for cancel-deletion
     * SC-007 race-safety.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AccountJpaEntity a WHERE a.phone = :phone")
    Optional<AccountJpaEntity> findByPhoneForUpdate(@Param("phone") String phone);

    boolean existsByPhone(String phone);

    /**
     * Targeted UPDATE for the login-by-phone-sms use case (FR-004 / V3
     * migration). Updates {@code last_login_at} + {@code updated_at} in
     * one statement so concurrent registers/saves do not contend with
     * the login bookkeeping write.
     */
    @Modifying
    @Query("UPDATE AccountJpaEntity a SET a.lastLoginAt = :lastLoginAt, a.updatedAt = :lastLoginAt"
            + " WHERE a.id = :accountId")
    int updateLastLoginAt(@Param("accountId") Long accountId, @Param("lastLoginAt") Instant lastLoginAt);

    /**
     * Pessimistic-write variant of {@link JpaRepository#findById} —
     * emits {@code SELECT … FOR UPDATE}, serialising concurrent callers
     * on the row until the surrounding transaction commits. Backs
     * {@code AccountRepository.findByIdForUpdate} for
     * anonymize-frozen-accounts SC-007 race-safety.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AccountJpaEntity a WHERE a.id = :id")
    Optional<AccountJpaEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * Id-only scan for FROZEN accounts past their grace period. Returns
     * a {@code Long} id list (mapped to {@link com.mbw.account.domain.model.AccountId}
     * by the impl) so the scheduler does not eagerly load full
     * Account aggregates for every row in the batch. The V7 partial
     * index {@code idx_account_freeze_until_active} drives this scan.
     */
    @Query("SELECT a.id FROM AccountJpaEntity a"
            + " WHERE a.status = 'FROZEN' AND a.freezeUntil <= :now"
            + " ORDER BY a.freezeUntil ASC")
    List<Long> findFrozenIdsWithExpiredGracePeriod(@Param("now") Instant now, Pageable pageable);
}
