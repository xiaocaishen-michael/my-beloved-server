package com.mbw.account.application.port;

/**
 * Query-verification outcome DTO returned by
 * {@link RealnameVerificationProvider#queryVerification(String)}. The
 * {@link Outcome} enum maps 1:1 to upstream Aliyun {@code SubCode} (per
 * AliyunRealnameClient T10) plus the explicit user-cancel outcome.
 *
 * @param outcome authoritative result of the verification session
 * @param failureMessage optional upstream-supplied detail; only populated
 *     for non-PASSED outcomes; never propagated to clients (trace / log only)
 */
public record QueryVerificationResult(Outcome outcome, String failureMessage) {

    /**
     * Outcomes returned by {@link RealnameVerificationProvider#queryVerification(String)}.
     *
     * <p>{@code USER_CANCELED} is callable distinctly from
     * {@code NAME_ID_NOT_MATCH} / {@code LIVENESS_FAILED} so
     * {@code ConfirmRealnameVerificationUseCase} can opt out of incrementing
     * the 24h retry counter (per FR-009 / SC-005).
     */
    public enum Outcome {
        PASSED,
        NAME_ID_NOT_MATCH,
        LIVENESS_FAILED,
        USER_CANCELED
    }
}
