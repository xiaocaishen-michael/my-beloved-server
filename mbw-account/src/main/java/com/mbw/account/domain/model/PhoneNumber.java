package com.mbw.account.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * E.164-formatted phone number value object.
 *
 * <p>M1.1 accepts mainland China numbers only (carrier prefixes 13–19),
 * matching {@code specs/auth/register-by-phone/spec.md} FR-001. The
 * single-column unique constraint on the persisted {@code phone} string
 * (FR-005) lives at the database layer, not here — this record only
 * encodes the format invariant.
 *
 * <p>International expansion (M2+) per spec adds a derived
 * {@code country_code} column populated from the prefix; this record's
 * {@link #countryCode()} accessor anticipates that boundary.
 */
public record PhoneNumber(String e164) {

    private static final Pattern MAINLAND_E164 = Pattern.compile("^\\+861[3-9]\\d{9}$");

    public PhoneNumber {
        Objects.requireNonNull(e164, "phone must not be null");
        if (!MAINLAND_E164.matcher(e164).matches()) {
            throw new IllegalArgumentException("INVALID_PHONE_FORMAT: " + e164);
        }
    }

    public String countryCode() {
        return "+86";
    }

    public String nationalNumber() {
        return e164.substring(3);
    }
}
