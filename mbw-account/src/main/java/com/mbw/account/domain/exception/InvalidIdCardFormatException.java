package com.mbw.account.domain.exception;

/**
 * Domain exception raised when a submitted ID card fails
 * {@code IdentityNumberValidator.validate} (length / character set / 行政区划
 * prefix / calendar date / GB 11643 check digit). Web advice maps to HTTP 400
 * with error code {@link #CODE}.
 *
 * <p>Intentionally does <b>not</b> carry the submitted ID number — id-card
 * digits are PII and must not surface in logs / traces (FR-008 / SC-002 /
 * realname-verification logging-leak IT T18).
 */
public class InvalidIdCardFormatException extends RuntimeException {

    public static final String CODE = "REALNAME_INVALID_ID_CARD_FORMAT";

    public InvalidIdCardFormatException() {
        super(CODE);
    }
}
