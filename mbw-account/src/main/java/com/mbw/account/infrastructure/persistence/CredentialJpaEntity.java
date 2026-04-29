package com.mbw.account.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JPA persistence shape for {@code account.credential}. Carries
 * {@code account_id} (FK) + {@code type} (PHONE | PASSWORD) + the
 * type-specific payload columns ({@code password_hash} for PASSWORD,
 * {@code last_used_at} for PHONE). The DB-level CHECK constraint
 * defined in V2 migration enforces "PASSWORD ⇒ password_hash NOT NULL,
 * PHONE ⇒ password_hash NULL".
 *
 * <p>Maps from sealed {@code Credential} domain hierarchy via
 * {@link AccountMapper#toCredentialEntity}; the type field is the
 * discriminator.
 */
@Entity
@Table(name = "credential", schema = "account")
public class CredentialJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(name = "password_hash", length = 60)
    private String passwordHash;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
