package com.mbw.account.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Server-side record of an issued refresh token (Phase 1.3,
 * {@code account.refresh_token} V5 migration).
 *
 * <p>Immutable: state changes (revoke) return a new instance. The id
 * is {@code null} for newly-constructed records (factory
 * {@link #createActive}); the repository assigns a real id on save and
 * downstream callers receive a record with {@link #id} populated.
 *
 * <p>Lifecycle predicates:
 *
 * <ul>
 *   <li>{@link #isActive(Instant)} — true iff {@code revokedAt == null}
 *       and {@code expiresAt} is in the future
 *   <li>{@link #revoke(Instant)} — must be called only once;
 *       double-revoke throws {@link IllegalStateException} so accidental
 *       double-rotation surfaces as a bug rather than silently
 *       overwriting the timestamp
 * </ul>
 */
public final class RefreshTokenRecord {

    private final RefreshTokenRecordId id; // null until saved
    private final RefreshTokenHash tokenHash;
    private final AccountId accountId;
    private final Instant expiresAt;
    private final Instant revokedAt; // null = active
    private final Instant createdAt;

    private RefreshTokenRecord(
            RefreshTokenRecordId id,
            RefreshTokenHash tokenHash,
            AccountId accountId,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt) {
        this.id = id;
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.revokedAt = revokedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * Build a fresh active record (id null, revokedAt null,
     * createdAt = now). Used by the issuing UseCases (register-by-phone,
     * login-by-*, refresh-token rotation) before
     * {@code RefreshTokenRepository.save}.
     */
    public static RefreshTokenRecord createActive(
            RefreshTokenHash tokenHash, AccountId accountId, Instant expiresAt, Instant now) {
        return new RefreshTokenRecord(/* id= */ null, tokenHash, accountId, expiresAt, /* revokedAt= */ null, now);
    }

    /**
     * Reconstitute from persistence (id present, all fields known).
     * Mirror of {@code Account.reconstitute}.
     */
    public static RefreshTokenRecord reconstitute(
            RefreshTokenRecordId id,
            RefreshTokenHash tokenHash,
            AccountId accountId,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt) {
        Objects.requireNonNull(id, "id must not be null");
        return new RefreshTokenRecord(id, tokenHash, accountId, expiresAt, revokedAt, createdAt);
    }

    public RefreshTokenRecordId id() {
        return id;
    }

    public RefreshTokenHash tokenHash() {
        return tokenHash;
    }

    public AccountId accountId() {
        return accountId;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /**
     * Return a new record with {@code revokedAt = at}. Throws if the
     * record is already revoked (idempotent revoke would mask
     * accidental double-rotation bugs).
     */
    public RefreshTokenRecord revoke(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (revokedAt != null) {
            throw new IllegalStateException("Already revoked at " + revokedAt);
        }
        return new RefreshTokenRecord(id, tokenHash, accountId, expiresAt, at, createdAt);
    }

    public boolean isActive(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
