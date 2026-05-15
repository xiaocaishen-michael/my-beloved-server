package com.mbw.account.domain.exception;

/**
 * Raised when a use case loads an account whose {@code status} is not
 * {@code ACTIVE} (i.e. {@code FROZEN} or {@code ANONYMIZED}).
 *
 * <p>Mapped by the account web layer to HTTP 401, byte-equal to the
 * missing-token / invalid-token / unknown-account paths per
 * specs/account/profile FR-009 anti-enumeration.
 */
public class AccountInactiveException extends RuntimeException {

    public AccountInactiveException() {
        super("Account inactive");
    }
}
