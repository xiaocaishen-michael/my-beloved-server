package com.mbw.account.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Six-digit numeric SMS verification code (FR-002).
 *
 * <p>Holds the plaintext code as submitted by the user or as freshly
 * generated for transmission. Persisted form is BCrypt-hashed (per
 * {@code specs/auth/register-by-phone/plan.md} infrastructure section);
 * that hashing concern lives outside this value object.
 */
public record VerificationCode(String value) {

    private static final Pattern SIX_DIGITS = Pattern.compile("^\\d{6}$");

    public VerificationCode {
        Objects.requireNonNull(value, "verification code must not be null");
        if (!SIX_DIGITS.matcher(value).matches()) {
            throw new IllegalArgumentException("INVALID_CODE_FORMAT");
        }
    }
}
