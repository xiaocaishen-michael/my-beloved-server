package com.mbw.account.domain.exception;

/**
 * Domain exception thrown by {@code InitiateRealnameVerificationUseCase} when
 * the caller's account is already in {@code RealnameStatus.VERIFIED} (terminal
 * per FR-015). Web advice maps to HTTP 409 with error code {@link #CODE}.
 *
 * <p>Idempotency note: this exception is the explicit refusal path; clients
 * receive 409 + an error body and are expected to fall back to GET
 * {@code /api/v1/realname/me} which returns the readonly verified profile.
 */
public class AlreadyVerifiedException extends RuntimeException {

    public static final String CODE = "REALNAME_ALREADY_VERIFIED";

    public AlreadyVerifiedException() {
        super(CODE);
    }
}
