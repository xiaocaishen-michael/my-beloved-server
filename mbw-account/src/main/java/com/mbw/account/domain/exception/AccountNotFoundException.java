package com.mbw.account.domain.exception;

/**
 * Raised when a use case loads an account by id (or other key) and the
 * row is missing.
 *
 * <p>Mapped by the account web layer to HTTP 401 (rather than 404) per
 * spec/account/account-profile FR-002 / FR-009 anti-enumeration: leaking
 * "this id is unknown" lets callers probe id-space, so the response is
 * indistinguishable from invalid-token / inactive-account paths.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException() {
        super("Account not found");
    }
}
