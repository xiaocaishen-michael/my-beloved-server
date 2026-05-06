package com.mbw.account.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * PG-backed SMS verification code, purpose-isolated per
 * {@link AccountSmsCodePurpose} (delete-account spec FR-007 / V8
 * migration).
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>{@link #create} — fresh in-memory record (id null, usedAt null)
 *   <li>{@code AccountSmsCodeRepository.save} — assigns id
 *   <li>{@link #isActive} — predicate for use cases before hash
 *       comparison
 *   <li>{@code AccountSmsCodeRepository.markUsed} — targeted UPDATE;
 *       keeps the record for audit trail while excluding it from future
 *       {@code findActive...} queries
 * </ol>
 *
 * <p>The plaintext 6-digit code never leaves the use case layer; only
 * the SHA-256 hex digest ({@link #codeHash}) is stored here.
 */
public final class AccountSmsCode {

    private final AccountSmsCodeId id; // null until saved
    private final AccountId accountId;
    private final String codeHash; // SHA-256 hex, 64 chars
    private final AccountSmsCodePurpose purpose;
    private final Instant expiresAt;
    private final Instant usedAt; // null = active
    private final Instant createdAt;

    private AccountSmsCode(
            AccountSmsCodeId id,
            AccountId accountId,
            String codeHash,
            AccountSmsCodePurpose purpose,
            Instant expiresAt,
            Instant usedAt,
            Instant createdAt) {
        this.id = id;
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.codeHash = Objects.requireNonNull(codeHash, "codeHash must not be null");
        this.purpose = Objects.requireNonNull(purpose, "purpose must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.usedAt = usedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * Factory for a freshly-generated code record (id null, usedAt null).
     * Use before {@code AccountSmsCodeRepository.save}.
     */
    public static AccountSmsCode create(
            AccountId accountId, String codeHash, Instant expiresAt, AccountSmsCodePurpose purpose, Instant now) {
        return new AccountSmsCode(/* id= */ null, accountId, codeHash, purpose, expiresAt, /* usedAt= */ null, now);
    }

    /**
     * Reconstitute from persistence (id present, all fields known).
     * Mirrors {@link com.mbw.account.domain.model.Account#reconstitute}.
     */
    public static AccountSmsCode reconstitute(
            AccountSmsCodeId id,
            AccountId accountId,
            String codeHash,
            AccountSmsCodePurpose purpose,
            Instant expiresAt,
            Instant usedAt,
            Instant createdAt) {
        Objects.requireNonNull(id, "id must not be null");
        return new AccountSmsCode(id, accountId, codeHash, purpose, expiresAt, usedAt, createdAt);
    }

    public AccountSmsCodeId id() {
        return id;
    }

    public AccountId accountId() {
        return accountId;
    }

    public String codeHash() {
        return codeHash;
    }

    public AccountSmsCodePurpose purpose() {
        return purpose;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant usedAt() {
        return usedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /**
     * Returns true iff the code has not been used and is not expired.
     * The repository's {@code findActiveByPurposeAndAccountId} pre-filters
     * on both conditions; this predicate is a defensive second check at the
     * application layer.
     */
    public boolean isActive(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return usedAt == null && expiresAt.isAfter(now);
    }
}
