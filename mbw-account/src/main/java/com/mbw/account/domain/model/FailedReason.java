package com.mbw.account.domain.model;

/**
 * Reason a realname-verification attempt landed in {@link RealnameStatus#FAILED}.
 *
 * <p>Mapped from Aliyun realname API SubCode (per AliyunRealnameClient T10) +
 * surfaced to clients via the FAILED-state response. Drives the retry-counter
 * logic in {@code RealnameProfile.withFailed} — {@link #USER_CANCELED} does
 * <b>not</b> increment the 24h failure counter (FR-009 / SC-005), since user
 * cancellations are not authentication failures.
 */
public enum FailedReason {
    /** Real name + ID card combination did not match the public-security record. */
    NAME_ID_MISMATCH,
    /** Liveness detection (face anti-spoof) failed. */
    LIVENESS_FAILED,
    /** User explicitly canceled the SDK flow before completion. */
    USER_CANCELED,
    /** Upstream provider returned an unrecoverable error (defensive bucket). */
    PROVIDER_ERROR
}
