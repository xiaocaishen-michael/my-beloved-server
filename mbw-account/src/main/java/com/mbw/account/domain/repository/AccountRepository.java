package com.mbw.account.domain.repository;

import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.PhoneNumber;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Domain-side persistence contract for {@link Account}.
 *
 * <p>Pure interface; the implementation in
 * {@code mbw-account.infrastructure.persistence} adapts JPA back to
 * this contract (per meta {@code modular-strategy.md} § "Repository
 * Methods (方式 A)"). Domain code never sees JPA types.
 */
public interface AccountRepository {

    Optional<Account> findByPhone(PhoneNumber phone);

    /**
     * Phone lookup with a row-level pessimistic write lock — concurrent
     * callers serialise on the same row until the surrounding transaction
     * commits. Used by cancel-deletion (SC-007) to race-safely admit
     * exactly one FROZEN → ACTIVE transition for a given phone: without
     * the lock, multiple threads can each pass an in-memory
     * {@code status == FROZEN} check on stale data and mutate independent
     * domain copies.
     *
     * <p>Must be called inside a {@code @Transactional} boundary;
     * lock release is at commit/rollback. Treat as a primitive — only
     * reach for it when the use case must serialise contention from the
     * load step rather than at write time.
     */
    Optional<Account> findByPhoneForUpdate(PhoneNumber phone);

    /**
     * Look up by id. Used by use cases that already hold an
     * {@link AccountId} (e.g. RefreshTokenUseCase resolving the
     * account linked to a refresh token record).
     */
    Optional<Account> findById(AccountId accountId);

    /**
     * Id lookup with a row-level pessimistic write lock — concurrent
     * callers serialise on the row until the surrounding transaction
     * commits. Used by anonymize-frozen-accounts (FR-007 / SC-007) so
     * the FROZEN → ANONYMIZED transition cannot race against an
     * in-flight cancel-deletion FROZEN → ACTIVE on the same account.
     *
     * <p>Must be called inside a {@code @Transactional} boundary; lock
     * release is at commit/rollback. Mirrors
     * {@link #findByPhoneForUpdate}'s primitive-style contract.
     */
    Optional<Account> findByIdForUpdate(AccountId accountId);

    /**
     * Scan a batch of {@link AccountId} values for FROZEN accounts whose
     * grace period has elapsed at {@code now}. Returns ids only — the
     * scheduler loads each individual account inside its own per-row
     * REQUIRES_NEW transaction (anonymize-frozen-accounts spec FR-002 /
     * FR-007). Ordered by {@code freeze_until ASC} so the
     * longest-overdue rows go first; capped by {@code limit} so a
     * backlog cannot starve other scheduled work.
     *
     * <p>Backed by the V7 partial index
     * {@code idx_account_freeze_until_active}, which already filters to
     * {@code WHERE status = 'FROZEN' AND freeze_until IS NOT NULL}.
     *
     * @param now   instant against which {@code freeze_until} is compared
     *              ({@code freeze_until <= now} is eligible)
     * @param limit max rows to return; the scheduler picks 100
     */
    List<AccountId> findFrozenWithExpiredGracePeriod(Instant now, int limit);

    /**
     * Pre-flight uniqueness check for FR-005. Note this does <b>not</b>
     * substitute for the database's UNIQUE constraint — concurrent
     * registers can both pass {@code !existsByPhone} and only one will
     * succeed at commit time. The check is for early happy-path exits
     * and to keep the FR-007 INVALID_CREDENTIALS path consistent.
     */
    boolean existsByPhone(PhoneNumber phone);

    /**
     * Persist a new or modified Account. For a freshly constructed
     * Account (no id), the implementation assigns the
     * database-generated id via {@link Account#assignId} and returns
     * the same instance.
     */
    Account save(Account account);

    /**
     * Targeted update of {@code last_login_at} (and {@code updated_at})
     * on a single account, used by the login-by-phone-sms use case
     * (FR-004 + V3 migration). Implementations issue a focused
     * {@code UPDATE ... WHERE id = ?} so login does not contend with
     * concurrent full-aggregate writes.
     *
     * @param accountId the account to update; must exist (caller has
     *     already loaded + verified it via {@link #findByPhone})
     * @param lastLoginAt UTC instant of the successful login
     * @throws IllegalStateException if no row matches {@code accountId}
     */
    void updateLastLoginAt(AccountId accountId, Instant lastLoginAt);
}
