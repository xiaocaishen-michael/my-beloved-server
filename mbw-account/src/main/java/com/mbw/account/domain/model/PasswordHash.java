package com.mbw.account.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * BCrypt-hashed password value object.
 *
 * <p>Wraps an already-hashed password produced by {@code BCrypt} (cost 8
 * per {@code spec/account/register-by-phone/plan.md}). Plaintext password
 * strength validation (FR-003) is the responsibility of
 * {@code PasswordPolicy} (T6), not this record — by the time a
 * PasswordHash exists, the plaintext has already been validated and
 * dropped.
 *
 * <p>Format check: BCrypt v2a / v2b / v2y output, 60 chars, leading
 * {@code $2[abxy]$<cost>$<22-char-salt><31-char-hash>}.
 */
public record PasswordHash(String value) {

    private static final Pattern BCRYPT_HASH = Pattern.compile("^\\$2[abxy]\\$\\d{2}\\$[./0-9A-Za-z]{53}$");

    public PasswordHash {
        Objects.requireNonNull(value, "password hash must not be null");
        if (!BCRYPT_HASH.matcher(value).matches()) {
            throw new IllegalArgumentException("INVALID_PASSWORD_HASH");
        }
    }
}
