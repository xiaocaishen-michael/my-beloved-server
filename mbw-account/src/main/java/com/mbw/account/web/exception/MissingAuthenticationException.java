package com.mbw.account.web.exception;

/**
 * Raised by {@code AuthenticatedAccountIdResolver} when a controller
 * method requires an authenticated {@code AccountId} but the request
 * carries no valid Bearer token (filter never set the request
 * attribute).
 *
 * <p>Mapped by {@code AccountWebExceptionAdvice} to a byte-equal 401
 * ProblemDetail along with {@code AccountNotFoundException} and
 * {@code AccountInactiveException} so the four anti-enumeration paths
 * (no token / bad token / unknown account / inactive account) are
 * indistinguishable at the response level.
 */
public class MissingAuthenticationException extends RuntimeException {

    public MissingAuthenticationException() {
        super("Missing or invalid authentication");
    }
}
