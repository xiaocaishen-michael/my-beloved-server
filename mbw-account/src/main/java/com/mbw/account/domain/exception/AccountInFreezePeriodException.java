package com.mbw.account.domain.exception;

import java.time.Instant;

/**
 * Domain exception for FROZEN account login attempts on phone-sms-auth.
 *
 * <p>Thrown when a phone-sms-auth request authenticates with a valid
 * SMS code but the matched account is in the 15-day delete-account
 * grace window (status == FROZEN). The web advice maps this to
 * HTTP 403 with code {@code ACCOUNT_IN_FREEZE_PERIOD} + extended
 * field {@code freezeUntil} (ISO 8601 UTC) so the client (per
 * spec C delete-account-cancel-deletion-ui) can trigger a "cancel
 * deletion?" intercept modal.
 *
 * <p><b>Disclosure boundary</b>: this exception is intentionally
 * NOT routed through anti-enumeration uniform 401 (per spec D
 * expose-frozen-account-status FR-001~FR-004). FROZEN status is
 * explicitly disclosed to support spec C login flow cancel-deletion
 * modal; ANONYMIZED status remains anti-enumeration-collapsed via
 * {@link InvalidCredentialsException}. Do not collapse this back
 * to {@code InvalidCredentialsException} without revisiting spec D.
 *
 * <p>Wall-clock note: callers should signal
 * {@link com.mbw.account.domain.service.TimingDefenseExecutor#executeInConstantTime}
 * to bypass the 400ms pad for this exception type (per spec D
 * FR-004 + CL-003) — the disclosure already exists, padding wastes
 * worker time without security gain.
 */
public class AccountInFreezePeriodException extends RuntimeException {

    public static final String CODE = "ACCOUNT_IN_FREEZE_PERIOD";

    private final Instant freezeUntil;

    public AccountInFreezePeriodException(Instant freezeUntil) {
        super(CODE);
        this.freezeUntil = freezeUntil;
    }

    public Instant getFreezeUntil() {
        return freezeUntil;
    }
}
