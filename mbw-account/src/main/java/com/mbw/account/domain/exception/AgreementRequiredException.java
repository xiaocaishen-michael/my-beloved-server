package com.mbw.account.domain.exception;

/**
 * Domain exception thrown when an initiate-verification request omits the
 * realname-auth agreement version (PRD § 5.7 / FR-005). The use case writes
 * an {@code account_agreement} row before any PII processing; missing
 * {@code agreementVersion} blocks the flow at the front gate.
 *
 * <p>Web advice maps to HTTP 400 with error code {@link #CODE}.
 */
public class AgreementRequiredException extends RuntimeException {

    public static final String CODE = "REALNAME_AGREEMENT_REQUIRED";

    public AgreementRequiredException() {
        super(CODE);
    }
}
