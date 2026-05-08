package com.mbw.account.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link RefreshTokenJpaEntity}.
 * Package-private; consumed by {@link RefreshTokenRepositoryImpl} only.
 */
interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenJpaEntity, Long> {

    Optional<RefreshTokenJpaEntity> findByTokenHash(String tokenHash);

    /**
     * Page through the active rows of one account. Walks the partial
     * index {@code idx_refresh_token_account_device_active} so even
     * accounts with thousands of historical revokes paginate cheaply
     * (device-management spec FR-001).
     */
    Page<RefreshTokenJpaEntity> findByAccountIdAndRevokedAtIsNull(Long accountId, Pageable pageable);

    /**
     * Revoke a single record (Phase 1.3 rotation). Guards with
     * {@code revokedAt IS NULL} so duplicate calls are no-ops at the SQL
     * level, not just at the domain level.
     */
    @Modifying
    @Query("UPDATE RefreshTokenJpaEntity r SET r.revokedAt = :revokedAt" + " WHERE r.id = :id AND r.revokedAt IS NULL")
    int revoke(@Param("id") Long id, @Param("revokedAt") Instant revokedAt);

    /**
     * Bulk-revoke all active records for an account (Phase 1.4
     * logout-all). The {@code idx_refresh_token_account_id_active}
     * partial index keeps this an index range scan even at scale.
     */
    @Modifying
    @Query("UPDATE RefreshTokenJpaEntity r SET r.revokedAt = :revokedAt"
            + " WHERE r.accountId = :accountId AND r.revokedAt IS NULL")
    int revokeAllForAccount(@Param("accountId") Long accountId, @Param("revokedAt") Instant revokedAt);
}
