package com.mbw.account.domain.exception;

/**
 * Domain exception covering all credential-related failure modes for
 * the register / login flows (FR-007 anti-enumeration design).
 *
 * <p>Per spec: any of {wrong code, expired code, invalidated code,
 * code not found, phone already registered, credential UNIQUE
 * conflict} surface uniformly as this exception, mapped to HTTP 401
 * by the web advice (T16). Differentiating these to the caller would
 * leak whether a phone is registered — defeating the FR-012
 * Template-A/B latency-aligned design.
 */
public class InvalidCredentialsException extends RuntimeException {

    public static final String CODE = "INVALID_CREDENTIALS";

    public InvalidCredentialsException() {
        super(CODE);
    }
}
