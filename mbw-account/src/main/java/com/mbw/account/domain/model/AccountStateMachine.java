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
}
