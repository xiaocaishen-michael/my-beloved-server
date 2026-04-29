package com.mbw.shared.api.sms;

/**
 * Outcome of {@link SmsCodeService#verify(String, String)}.
 *
 * <p>Conveys three signals so callers can route the response without a
 * second round trip to Redis:
 *
 * <ul>
 *   <li>{@link #success}: did the submitted code match the stored hash
 *   <li>{@link #attempts}: total number of verify attempts recorded
 *       against this code (incl. the current one if it failed)
 *   <li>{@link #invalidated}: has the code crossed the failure threshold
 *       and been permanently rejected within its TTL window
 * </ul>
 *
 * <p>{@code success=true} implies {@code invalidated=false} and
 * {@code attempts} reflects the count up to and including the
 * successful match.
 */
public record AttemptOutcome(boolean success, int attempts, boolean invalidated) {

    public AttemptOutcome {
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be non-negative, got " + attempts);
        }
    }
}
