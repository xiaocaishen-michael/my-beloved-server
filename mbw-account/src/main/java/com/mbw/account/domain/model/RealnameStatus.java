package com.mbw.account.domain.model;

/**
 * Realname-verification lifecycle status (realname-verification spec FR-002).
 *
 * <p>Transitions (enforced by {@code RealnameStateMachine} in T3):
 *
 * <ul>
 *   <li>{@code UNVERIFIED} → {@code PENDING} (initiate verification)
 *   <li>{@code PENDING} → {@code VERIFIED} (PASSED outcome from provider)
 *   <li>{@code PENDING} → {@code FAILED} (NAME_ID_NOT_MATCH / LIVENESS_FAILED / USER_CANCELED)
 *   <li>{@code FAILED} → {@code PENDING} (user retries)
 *   <li>{@code VERIFIED} is terminal — FR-015 forbids unbinding
 * </ul>
 */
public enum RealnameStatus {
    UNVERIFIED,
    PENDING,
    VERIFIED,
    FAILED
}
