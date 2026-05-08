package com.mbw.account.domain.service;

import com.mbw.account.domain.model.RealnameStatus;

/**
 * Single source of truth for {@link RealnameStatus} transition legality
 * (realname-verification spec T3).
 *
 * <p>Stateless utility — exposed via a single static method
 * {@link #assertCanTransition(RealnameStatus, RealnameStatus)}, mirroring the
 * call style of {@code AccountStateMachine} for cross-aggregate consistency.
 * {@code RealnameProfile.with*} delegates here so the matrix has one home and
 * test ({@code RealnameStateMachineTest}).
 *
 * <p>Legal: UNVERIFIED→PENDING, PENDING→{VERIFIED, FAILED}, FAILED→PENDING.
 * VERIFIED is terminal (FR-015); UNVERIFIED→VERIFIED / UNVERIFIED→FAILED /
 * FAILED→VERIFIED (must traverse PENDING) / VERIFIED→* are all rejected.
 */
public final class RealnameStateMachine {

    private RealnameStateMachine() {}

    /**
     * Throw {@link IllegalStateException} unless {@code from → to} is a legal
     * realname-lifecycle transition.
     *
     * @throws IllegalStateException if the transition is not in the legal set
     */
    public static void assertCanTransition(RealnameStatus from, RealnameStatus to) {
        boolean legal =
                switch (from) {
                    case UNVERIFIED -> to == RealnameStatus.PENDING;
                    case PENDING -> to == RealnameStatus.VERIFIED || to == RealnameStatus.FAILED;
                    case FAILED -> to == RealnameStatus.PENDING;
                    case VERIFIED -> false;
                };
        if (!legal) {
            throw new IllegalStateException("Illegal realname status transition: " + from + " -> " + to);
        }
    }
}
