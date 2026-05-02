package com.mbw.account.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SHA-256 hex digest of a refresh token, value object.
 *
 * <p>The raw refresh token (256-bit base64url, ~43 chars) is what the
 * client holds; the server only ever stores its SHA-256 digest in
 * {@code account.refresh_token.token_hash} (V5 migration). Verification
 * flow: client sends raw token → {@code RefreshTokenHasher} hashes it
 * → {@link RefreshTokenHash} value used as the lookup key.
 *
 * <p>Format check: 64 lowercase hex characters. Uppercase hex is
 * deliberately rejected so the persisted form is canonical (downstream
 * lookups never need {@code lower()} on either side).
 */
public record RefreshTokenHash(String value) {

    private static final Pattern LOWERCASE_HEX_64 = Pattern.compile("^[0-9a-f]{64}$");

    public RefreshTokenHash {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (!LOWERCASE_HEX_64.matcher(value).matches()) {
            throw new IllegalArgumentException("value must be 64 lowercase hex chars");
        }
    }
}
