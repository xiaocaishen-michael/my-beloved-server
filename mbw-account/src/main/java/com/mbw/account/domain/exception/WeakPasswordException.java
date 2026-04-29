package com.mbw.account.domain.exception;

/**
 * Domain exception raised by {@code PasswordPolicy.validate} when the
 * submitted plaintext password fails FR-003 strength requirements
 * (≥ 8 chars + uppercase + lowercase + digit). Mapped to HTTP 422 with
 * error code {@link #CODE} by the account web exception advice.
 *
 * <p>The message contains the failing rule (e.g. "must be at least 8
 * characters") for log diagnostics; <b>never</b> echoes the submitted
 * password.
 */
public class WeakPasswordException extends RuntimeException {

    public static final String CODE = "INVALID_PASSWORD";

    public WeakPasswordException(String reason) {
        super(CODE + ": " + reason);
    }
}
