package com.mbw.account.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Password-based credential — optional at register-by-phone (FR-003).
 *
 * <p>Holds the BCrypt hash output, never the plaintext. Plaintext
 * strength validation runs in {@code PasswordPolicy} (T6) before the
 * hash is computed; by the time a {@code PasswordCredential} exists,
 * the plaintext has already been zeroed.
 *
 * <p>The {@code (account_id, type=PASSWORD)} pair is unique at the
 * database layer; future password rotation will replace the row in
 * place rather than appending.
 */
public record PasswordCredential(AccountId account, PasswordHash hash, Instant createdAt) implements Credential {

    public PasswordCredential {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(hash, "hash must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
