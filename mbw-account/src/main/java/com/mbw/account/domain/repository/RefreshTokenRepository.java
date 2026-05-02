package com.mbw.account.domain.repository;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.RefreshTokenHash;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.model.RefreshTokenRecordId;
import java.time.Instant;
import java.util.Optional;

/**
 * Domain-side persistence contract for {@link RefreshTokenRecord}
 * (Phase 1.3 / V5 migration).
 *
 * <p>Pure interface; the implementation in
 * {@code mbw-account.infrastructure.persistence} adapts JPA back to
 * this contract per the {@code AccountRepository} pattern.
 */
public interface RefreshTokenRepository {

    /**
     * Persist a freshly-issued record (id null) — the implementation
     * assigns the database-generated id and returns a record with the
     * id populated. Existing records (id non-null) are saved-as-is.
     */
    RefreshTokenRecord save(RefreshTokenRecord record);

    /**
     * Look up by {@link RefreshTokenHash}. Returns empty when no row
     * matches (attacker-supplied or 1.3-pre-impl legacy token, both
     * fold to {@code INVALID_CREDENTIALS} at the use case layer).
     */
    Optional<RefreshTokenRecord> findByTokenHash(RefreshTokenHash hash);

    /**
     * Set {@code revoked_at} on a single row. Implementation guards
     * with {@code WHERE id = ? AND revoked_at IS NULL} so a duplicate
     * revoke is a no-op. Returns the number of rows affected — 0 means
     * the row was already revoked (concurrent rotation lost the race),
     * which the caller should surface as a rotation failure so the
     * surrounding transaction rolls back.
     */
    int revoke(RefreshTokenRecordId id, Instant revokedAt);

    /**
     * Bulk-revoke all active records for an account, used by
     * Phase 1.4 logout-all. Returns the number of rows affected;
     * the partial index {@code idx_refresh_token_account_id_active}
     * keeps this cheap even at large table sizes.
     */
    int revokeAllForAccount(AccountId accountId, Instant revokedAt);
}
