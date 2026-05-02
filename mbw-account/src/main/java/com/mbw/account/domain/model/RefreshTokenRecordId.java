package com.mbw.account.domain.model;

/**
 * Identity wrapper for {@link RefreshTokenRecord}. Mirrors the
 * {@link AccountId} pattern — keeps domain method signatures off
 * {@code Long} so a "wrong-aggregate-id" misuse becomes a
 * compile-time error.
 */
public record RefreshTokenRecordId(long value) {

    public RefreshTokenRecordId {
        if (value <= 0L) {
            throw new IllegalArgumentException("id must be positive");
        }
    }
}
