package com.mbw.account.domain.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mbw.account.domain.model.RealnameStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link RealnameStateMachine} (realname-verification spec T3).
 *
 * <p>Pins the realname lifecycle transition matrix as a single source of truth
 * (extracted from {@code RealnameProfile.requireLegalTransition}, the inline
 * version that landed with T1). The aggregate now delegates here, so any
 * future change to the matrix only needs to update this file + impl.
 *
 * <p>Legal transitions:
 *
 * <ul>
 *   <li>UNVERIFIED → PENDING (initiate)
 *   <li>PENDING → VERIFIED (PASSED outcome)
 *   <li>PENDING → FAILED (any non-PASSED outcome)
 *   <li>FAILED → PENDING (retry per FR-009)
 * </ul>
 *
 * <p>Everything else is rejected — VERIFIED is terminal (FR-015), and shortcuts
 * like UNVERIFIED→VERIFIED bypass the cipher / hash / provider lifecycle.
 */
class RealnameStateMachineTest {

    @ParameterizedTest
    @CsvSource({"UNVERIFIED, PENDING", "PENDING, VERIFIED", "PENDING, FAILED", "FAILED, PENDING"})
    void legal_transitions_do_not_throw(RealnameStatus from, RealnameStatus to) {
        assertThatCode(() -> RealnameStateMachine.assertCanTransition(from, to)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
        "UNVERIFIED, VERIFIED", // skip-PENDING shortcut
        "UNVERIFIED, FAILED", // skip-PENDING shortcut
        "VERIFIED, UNVERIFIED", // VERIFIED is terminal (FR-015)
        "VERIFIED, PENDING", // ditto
        "VERIFIED, FAILED", // ditto
        "FAILED, VERIFIED" // must re-traverse PENDING (provider re-call)
    })
    void illegal_transitions_throw_illegal_state(RealnameStatus from, RealnameStatus to) {
        assertThatThrownBy(() -> RealnameStateMachine.assertCanTransition(from, to))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name());
    }
}
