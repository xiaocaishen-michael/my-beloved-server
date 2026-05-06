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
    private DisplayName displayName;
    private Instant freezeUntil;

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
     *
     * <p>Delegates to the 7-arg overload with {@code displayName=null};
     * use the 7-arg form when the persisted row carries a non-null
     * {@code display_name} (account-profile FR-007 / V6 migration).
     */
    public static Account reconstitute(
            AccountId id,
            PhoneNumber phone,
            AccountStatus status,
            Instant createdAt,
            Instant updatedAt,
            Instant lastLoginAt) {
        return reconstitute(id, phone, status, createdAt, updatedAt, lastLoginAt, /* displayName= */ null);
    }

    /**
     * Reconstruct including {@link #displayName}. Delegates to the 8-arg
     * overload with {@code freezeUntil=null}; use the 8-arg form when the
     * persisted row carries a non-null {@code freeze_until}
     * (delete-account spec / V7 migration).
     */
    public static Account reconstitute(
            AccountId id,
            PhoneNumber phone,
            AccountStatus status,
            Instant createdAt,
            Instant updatedAt,
            Instant lastLoginAt,
            DisplayName displayName) {
        return reconstitute(id, phone, status, createdAt, updatedAt, lastLoginAt, displayName, /* freezeUntil= */ null);
    }

    /**
     * Full reconstitute overload including {@link #freezeUntil}. Used by
     * the repository implementation when the persisted row carries a
     * non-null {@code freeze_until} (delete-account spec / V7 migration).
     */
    public static Account reconstitute(
            AccountId id,
            PhoneNumber phone,
            AccountStatus status,
            Instant createdAt,
            Instant updatedAt,
            Instant lastLoginAt,
            DisplayName displayName,
            Instant freezeUntil) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Account account = new Account(phone, createdAt);
        account.id = id;
        account.status = status;
        account.updatedAt = updatedAt;
        account.lastLoginAt = lastLoginAt;
        account.displayName = displayName;
        account.freezeUntil = freezeUntil;
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

    public DisplayName displayName() {
        return displayName;
    }

    public Instant freezeUntil() {
        return freezeUntil;
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

    /**
     * Package-private mutator for the ACTIVE → FROZEN transition
     * (delete-account spec FR-006). Only callable through
     * {@link AccountStateMachine#markFrozen} so the single-entry
     * constraint lives in one place. Writes {@link #status},
     * {@link #freezeUntil}, and refreshes {@link #updatedAt}.
     *
     * @throws IllegalStateException if {@code status} is not {@code ACTIVE}
     */
    void markFrozen(Instant freezeUntil, Instant now) {
        Objects.requireNonNull(freezeUntil, "freezeUntil must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (this.status != AccountStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Account status is " + this.status + ", cannot transition to FROZEN (only ACTIVE permitted)");
        }
        this.status = AccountStatus.FROZEN;
        this.freezeUntil = freezeUntil;
        this.updatedAt = now;
    }

    /**
     * Package-private mutator for the FROZEN → ACTIVE transition
     * (cancel-deletion spec FR-001 / M1.3). Only callable through
     * {@link AccountStateMachine#markActiveFromFrozen} so the
     * "FROZEN + grace not expired" invariant lives in one place.
     * Restores {@link #status} to ACTIVE, clears {@link #freezeUntil},
     * and refreshes {@link #updatedAt}.
     *
     * <p>Rejects with {@link IllegalStateException} carrying message
     * {@code ACCOUNT_NOT_FROZEN_IN_GRACE} on any of: status != FROZEN,
     * freezeUntil null, or freezeUntil <= now (grace expired). The use
     * case layer maps this single message to INVALID_CREDENTIALS for
     * anti-enumeration (FR-006 / SC-002).
     *
     * @throws IllegalStateException if status is not FROZEN, freezeUntil
     *     is null, or freezeUntil <= now
     */
    void markActiveFromFrozen(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (this.status != AccountStatus.FROZEN || this.freezeUntil == null || !this.freezeUntil.isAfter(now)) {
            throw new IllegalStateException("ACCOUNT_NOT_FROZEN_IN_GRACE");
        }
        this.status = AccountStatus.ACTIVE;
        this.freezeUntil = null;
        this.updatedAt = now;
    }

    /**
     * Package-private mutator for onboarding / profile-update
     * (account-profile FR-005). Only callable through
     * {@link AccountStateMachine#changeDisplayName} so the
     * "ACTIVE-only" invariant (FR-009) lives in a single place. Writes
     * {@link #displayName} and refreshes {@link #updatedAt}.
     */
    void setDisplayName(DisplayName displayName, Instant at) {
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (this.status != AccountStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Account status is " + this.status + ", cannot changeDisplayName (only ACTIVE permitted)");
        }
        this.displayName = displayName;
        this.updatedAt = at;
    }
}
