package com.mbw.account.domain.exception;

/**
 * Domain exception for all deletion-code verification failures
 * (delete-account spec FR-002 anti-enumeration).
 *
 * <p>Covers: code not found / hash mismatch / expired / already used.
 * All map to HTTP 401 with the same {@link #CODE} so callers cannot
 * distinguish which arm fired.
 */
public class InvalidDeletionCodeException extends RuntimeException {

    public static final String CODE = "INVALID_DELETION_CODE";

    public InvalidDeletionCodeException() {
        super(CODE);
    }
}
