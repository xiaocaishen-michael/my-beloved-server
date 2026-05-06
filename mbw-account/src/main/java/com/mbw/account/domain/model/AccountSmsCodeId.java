package com.mbw.account.domain.model;

/**
 * Identity wrapper for {@link AccountSmsCode}. Mirrors the
 * {@link RefreshTokenRecordId} pattern — keeps domain method signatures
 * off raw {@code Long} so wrong-aggregate-id misuse is a compile error.
 */
public record AccountSmsCodeId(long value) {

    public AccountSmsCodeId {
        if (value <= 0L) {
            throw new IllegalArgumentException("id must be positive");
        }
    }
}
