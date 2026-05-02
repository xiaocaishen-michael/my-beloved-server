package com.mbw.account.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Account aggregate root.
 *
 * <p>Encapsulates a user account's identity and lifecycle state.
 * Mutable by design (DDD aggregate root pattern); transitions go
 * through controlled methods or, for status changes, exclusively via
 * {@link AccountStateMachine} (FR-004).
 *
 * <p>Cross-aggregate invariants — e.g. "an ACTIVE account must have at
 * least one phone {@code Credential}" — are enforced at the use case
 * and database layers (V2 migration unique index +
 * {@code RegisterByPhoneUseCase} transactional save), not on this
 * record. This aggregate stays small so each invariant has a single
 * authoritative home.
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>{@code new Account(phone, createdAt)} — fresh in-memory account
 *       (no id, no status)
 *   <li>{@link AccountStateMachine#activate} — transition to
 *       {@link AccountStatus#ACTIVE}
 *   <li>{@code AccountRepository.save} — assigns id via
 *       {@link #assignId}
 *   <li>future use cases (freeze / anonymize) extend the state machine
 * </ol>
 */
public final class Account {

    private AccountId id;
    private final PhoneNumber phone;
    private AccountStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant lastLoginAt;

    public Account(PhoneNumber phone, Instant createdAt) {
        this.phone = Objects.requireNonNull(phone, "phone must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = createdAt;
    }

    /**
     * Reconstruct an Account from its persisted state. Used by the
     * repository implementation; not part of the public domain
     * construction path.
     */
    public static Account reconstitute(
            AccountId id, PhoneNumber phone, AccountStatus status, Instant createdAt, Instant updatedAt) {
        return reconstitute(id, phone, status, createdAt, updatedAt, /* lastLoginAt= */ null);
    }

    /**
     * Reconstruct including {@link #lastLoginAt}. Use this overload when
     * the persisted row carries a non-null {@code last_login_at}; the
     * 5-arg overload remains for older callers that pre-date the
     * {@code login-by-phone-sms} use case (FR-004 / V3 migration).
     */
    public static Account reconstitute(
            AccountId id,
            PhoneNumber phone,
            AccountStatus status,
            Instant createdAt,
            Instant updatedAt,
            Instant lastLoginAt) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Account account = new Account(phone, createdAt);
        account.id = id;
        account.status = status;
        account.updatedAt = updatedAt;
        account.lastLoginAt = lastLoginAt;
        return account;
    }

    public AccountId id() {
        return id;
    }

    public PhoneNumber phone() {
        return phone;
    }

    public AccountStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant lastLoginAt() {
        return lastLoginAt;
    }

    /**
     * Assign the database-generated id after an initial save. Cannot be
     * called twice — once an id is set the aggregate is sealed against
     * identity drift.
     */
    public void assignId(AccountId id) {
        Objects.requireNonNull(id, "id must not be null");
        if (this.id != null) {
            throw new IllegalStateException("AccountId already assigned: " + this.id);
        }
        this.id = id;
    }

    /**
     * Package-private state mutator. Only {@link AccountStateMachine}
     * (same package) is allowed to invoke this; it is the single
     * gateway through which {@link AccountStatus#ACTIVE} can be
     * reached, satisfying the FR-004 single-entry constraint.
     */
    void markActive(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (this.status != null) {
            throw new IllegalStateException(
                    "Account already in status " + this.status + ", cannot transition to ACTIVE");
        }
        this.status = AccountStatus.ACTIVE;
        this.updatedAt = at;
    }

    /**
     * Package-private mutator for successful-login bookkeeping
     * (login-by-phone-sms FR-004). Only callable through
     * {@link AccountStateMachine#markLoggedIn} so the canLogin invariant
     * stays in one place. Updates {@link #lastLoginAt} and
     * {@link #updatedAt} to {@code at}.
     */
    void markLoggedIn(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (this.status != AccountStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Account status is " + this.status + ", cannot mark loggedIn (only ACTIVE permitted)");
        }
        this.lastLoginAt = at;
        this.updatedAt = at;
    }
}
