package com.mbw.account.domain.exception;

/**
 * Raised by {@code ConfirmRealnameVerificationUseCase} when the
 * authenticated caller's {@code accountId} does not match the
 * {@code accountId} that owns the {@code providerBizId} being polled
 * (realname-verification spec T14, plan.md § core flow step 1: "profile.accountId
 * != cmd.callerAccountId → 403 (越权)").
 *
 * <p>Mapped to HTTP 403. Returning 403 (rather than collapsing to 404 via
 * anti-enumeration) is safe because {@code providerBizId} is a UUID — an
 * attacker cannot enumerate it, so disclosure of "exists but not yours" leaks
 * no actionable information.
 */
public class RealnameProfileAccessDeniedException extends RuntimeException {

    public static final String CODE = "REALNAME_ACCESS_DENIED";

    public RealnameProfileAccessDeniedException() {
        super(CODE);
    }
}
