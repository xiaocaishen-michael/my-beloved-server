package com.mbw.account.infrastructure.persistence;

import com.mbw.account.domain.model.AccountSmsCodePurpose;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link AccountSmsCodeJpaEntity}.
 * Package-private; consumed by {@link AccountSmsCodeRepositoryImpl} only.
 */
interface AccountSmsCodeJpaRepository extends JpaRepository<AccountSmsCodeJpaEntity, Long> {

    /**
     * Returns the most-recently-created active code matching the given
     * purpose and accountId. "Active" = usedAt IS NULL AND expiresAt &gt;
     * now. The partial index
     * {@code idx_account_sms_code_account_purpose_active} makes this an
     * efficient index scan.
     */
    @Query("SELECT e FROM AccountSmsCodeJpaEntity e"
            + " WHERE e.accountId = :accountId"
            + " AND e.purpose = :purpose"
            + " AND e.usedAt IS NULL"
            + " AND e.expiresAt > :now"
            + " ORDER BY e.createdAt DESC"
            + " LIMIT 1")
    Optional<AccountSmsCodeJpaEntity> findActive(
            @Param("accountId") Long accountId,
            @Param("purpose") AccountSmsCodePurpose purpose,
            @Param("now") Instant now);

    /**
     * Mark a code as used — targeted UPDATE so the record is retained
     * for audit while the partial index excludes it from future active
     * lookups.
     */
    @Modifying
    @Query("UPDATE AccountSmsCodeJpaEntity e SET e.usedAt = :usedAt WHERE e.id = :id")
    void markUsed(@Param("id") Long id, @Param("usedAt") Instant usedAt);

    /**
     * Hard-delete all rows for {@code accountId}. Backs
     * {@code AccountSmsCodeRepository.deleteAllByAccountId} for
     * anonymize-frozen-accounts FR-004.
     */
    @Modifying
    @Query("DELETE FROM AccountSmsCodeJpaEntity e WHERE e.accountId = :accountId")
    void deleteByAccountId(@Param("accountId") Long accountId);
}
