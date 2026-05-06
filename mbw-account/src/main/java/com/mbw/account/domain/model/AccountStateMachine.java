package com.mbw.account.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Sole entry point for {@link Account} status transitions (FR-004).
 *
 * <p>Co-located in the {@code domain.model} package so it can invoke
 * the aggregate's package-private mutators while remaining the only
 * thing outside {@code Account} itself that holds that visibility.
 * Naming-wise this is a domain service (per CLAUDE.md § 二 naming);
 * Java's lack of a friend mechanism makes co-location the cleanest way
 * to enforce "one and only one" status-transition entry.
 *
 * <p>M1.1 supports a single transition: {@code (none) → ACTIVE}.
 * Future use cases (freeze / anonymize) will add additional methods
 * here; {@code Account.markX} mutators stay package-private so this
 * class remains the single gateway.
 */
public final class AccountStateMachine {

    private AccountStateMachine() {}

    /**
     * Transition a freshly-constructed Account to
     * {@link AccountStatus#ACTIVE}. Idempotent only across re-entry on
     * the same in-flight account; once committed, a second activation
     * attempt fails fast with {@link IllegalStateException}.
     *
     * @param account the account to activate; must have null status
     * @param at the activation instant; written to
     *     {@link Account#updatedAt}
     * @return the same account (mutated in place) for fluent use case
     *     composition
     * @throws IllegalStateException if {@code account.status()} is not
     *     null
     */
    public static Account activate(Account account, Instant at) {
        Objects.requireNonNull(account, "account must not be null");
        account.markActive(at);
        return account;
    }

    /**
     * Record a successful login on an {@link AccountStatus#ACTIVE}
     * account (FR-004 / login-by-phone-sms). Sets
     * {@link Account#lastLoginAt} and {@link Account#updatedAt} to
     * {@code at}; rejects non-ACTIVE accounts with
     * {@link IllegalStateException}.
     *
     * @param account the account; status must be {@code ACTIVE}
     * @param at the login instant; UTC
     * @return the same account (mutated in place) for fluent use case
     *     composition
     */
    public static Account markLoggedIn(Account account, Instant at) {
        Objects.requireNonNull(account, "account must not be null");
        account.markLoggedIn(at);
        return account;
    }

    /**
     * Read-only predicate for "may this account log in" (FR-003 of the
     * login-by-phone-sms use case). Returns {@code true} only for
     * {@link AccountStatus#ACTIVE}; all other states (including
     * {@code null}, i.e. an account never persisted) return false.
     *
     * <p>UseCases (e.g. {@code LoginByPhoneSmsUseCase}) call this before
     * {@link #markLoggedIn} so the negative path collapses to the same
     * INVALID_CREDENTIALS response (FR-006), preventing enumeration of
     * "frozen vs active" via differential responses.
     */
    public static boolean canLogin(Account account) {
        Objects.requireNonNull(account, "account must not be null");
        return account.status() == AccountStatus.ACTIVE;
    }

    /**
     * Transition an {@link AccountStatus#ACTIVE} account to
     * {@link AccountStatus#FROZEN} (delete-account spec FR-006 /
     * M1.3). Writes {@link Account#freezeUntil} and
     * {@link Account#updatedAt}; rejects non-ACTIVE accounts with
     * {@link IllegalStateException}.
     *
     * @param account the account; status must be {@code ACTIVE}
     * @param freezeUntil when the account becomes eligible for
     *     anonymization (= now + 15 days per spec FR-004)
     * @param now the transition instant; UTC
     * @return the same account (mutated in place) for fluent use case
     *     composition
     * @throws IllegalStateException if {@code account.status()} is not
     *     ACTIVE
     */
    public static Account markFrozen(Account account, Instant freezeUntil, Instant now) {
        Objects.requireNonNull(account, "account must not be null");
        account.markFrozen(freezeUntil, now);
        return account;
    }

    /**
     * Update the account's {@link DisplayName} (account-profile spec
     * FR-005). Only ACTIVE accounts may transition; FROZEN /
     * ANONIMIZED rejection happens here so use cases get a uniform
     * IllegalStateException to map to the 401 anti-enumeration path
     * (FR-009).
     *
     * @param account the target account; status must be ACTIVE
     * @param displayName the new value, validated by its constructor
     * @param at the update instant; written to {@link Account#updatedAt}
     * @return the same account (mutated in place) for fluent use case
     *     composition
     * @throws IllegalStateException if {@code account.status()} is not
     *     ACTIVE
     */
    public static Account changeDisplayName(Account account, DisplayName displayName, Instant at) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(at, "at must not be null");
        account.setDisplayName(displayName, at);
        return account;
    }
}
