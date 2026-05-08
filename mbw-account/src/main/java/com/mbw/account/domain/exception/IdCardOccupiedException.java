package com.mbw.account.domain.exception;

/**
 * Domain exception thrown when a submitted ID card hash collides with another
 * account already in {@code PENDING} or {@code VERIFIED} state — FR-013
 * (one ID card belongs to at most one account at a time). Surfaces both as
 * the proactive lookup branch and as the {@code DataIntegrityViolation}
 * fallback against the partial unique index
 * {@code uk_realname_profile_id_card_hash}.
 *
 * <p>Web advice maps to HTTP 409 with error code {@link #CODE}. Intentionally
 * does <b>not</b> disclose which account holds the conflicting hash.
 */
public class IdCardOccupiedException extends RuntimeException {

    public static final String CODE = "REALNAME_ID_CARD_OCCUPIED";

    public IdCardOccupiedException() {
        super(CODE);
    }
}
