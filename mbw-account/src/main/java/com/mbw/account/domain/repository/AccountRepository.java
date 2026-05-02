package com.mbw.account.domain.repository;

import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.PhoneNumber;
import java.time.Instant;
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
