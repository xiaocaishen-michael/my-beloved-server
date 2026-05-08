package com.mbw.account.domain.exception;

/**
 * Raised by {@code ConfirmRealnameVerificationUseCase} when a client polls
 * {@code GET /verifications/{providerBizId}} for a {@code bizId} that doesn't
 * match any persisted {@code RealnameProfile} (realname-verification
 * spec T14, plan.md § core flow step 1).
 *
 * <p>Mapped to HTTP 404. Disclosure is acceptable here: {@code providerBizId}
 * is a server-issued UUID with no enumeration value to attackers.
 */
public class RealnameProfileNotFoundException extends RuntimeException {

    public static final String CODE = "REALNAME_PROFILE_NOT_FOUND";

    public RealnameProfileNotFoundException() {
        super(CODE);
    }
}
