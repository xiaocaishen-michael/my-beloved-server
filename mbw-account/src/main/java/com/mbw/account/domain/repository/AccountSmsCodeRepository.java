package com.mbw.account.domain.repository;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountSmsCode;
import com.mbw.account.domain.model.AccountSmsCodeId;
import com.mbw.account.domain.model.AccountSmsCodePurpose;
import java.time.Instant;
import java.util.Optional;

/**
 * Domain-side persistence contract for {@link AccountSmsCode}
 * (delete-account spec / V8 migration).
 *
 * <p>Pure interface; the implementation in
 * {@code mbw-account.infrastructure.persistence} adapts JPA back to
 * this contract per the {@link AccountRepository} pattern.
 */
public interface AccountSmsCodeRepository {

    /**
     * Persist a fresh code record (id null) — the implementation assigns
     * the database-generated id and returns the record with id populated.
     */
    AccountSmsCode save(AccountSmsCode code);

    /**
     * Find the most-recently-created active (unused + not expired) code
     * for the given {@code purpose} and {@code accountId}.
     *
     * <p>"Active" is defined as {@code used_at IS NULL AND expires_at >
     * now}. The partial index
     * {@code idx_account_sms_code_account_purpose_active} makes this
     * query an index scan over a small set.
     */
    Optional<AccountSmsCode> findActiveByPurposeAndAccountId(
            AccountSmsCodePurpose purpose, AccountId accountId, Instant now);

    /**
     * Mark a code as used via a targeted {@code UPDATE ... SET used_at = ?}
     * so the record is excluded from future {@code findActive...} queries
     * while remaining for audit purposes. Executed within the surrounding
     * transaction (delete-account use case owns the transaction boundary).
     */
    void markUsed(AccountSmsCodeId id, Instant now);
}
