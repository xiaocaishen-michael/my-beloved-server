package com.mbw.account.domain.exception;

/**
 * Domain exception raised by {@code PhonePolicy.validate} when the
 * submitted phone number does not satisfy FR-001
 * (E.164 + mainland China carrier prefix). Mapped to HTTP 422 with
 * error code {@link #CODE} by the account web exception advice.
 */
public class InvalidPhoneFormatException extends RuntimeException {

    public static final String CODE = "INVALID_PHONE_FORMAT";

    private final String submittedPhone;

    public InvalidPhoneFormatException(String submittedPhone) {
        super(CODE + ": " + submittedPhone);
        this.submittedPhone = submittedPhone;
    }

    public String getSubmittedPhone() {
        return submittedPhone;
    }
}
