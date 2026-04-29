package com.mbw.account.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Phone-based credential — the primary credential established at
 * register-by-phone (FR-005). {@link #lastUsedAt} captures the most
 * recent successful registration / login via this phone, supporting
 * future fraud-detection and session-management use cases.
 *
 * <p>The {@code (account_id, type=PHONE)} pair is unique at the database
 * layer (V2 migration UNIQUE index uk_credential_account_type) so an
 * account can hold at most one phone credential.
 */
public record PhoneCredential(AccountId account, PhoneNumber phone, Instant lastUsedAt) implements Credential {

    public PhoneCredential {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(phone, "phone must not be null");
        Objects.requireNonNull(lastUsedAt, "lastUsedAt must not be null");
    }
}
