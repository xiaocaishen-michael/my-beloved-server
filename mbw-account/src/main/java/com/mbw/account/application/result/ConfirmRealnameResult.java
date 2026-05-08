package com.mbw.account.application.result;

import com.mbw.account.domain.model.FailedReason;
import com.mbw.account.domain.model.RealnameStatus;
import java.time.Instant;

/**
 * Use-case-level projection of a confirm-result poll
 * (realname-verification spec T14, plan.md § ConfirmRealnameVerificationUseCase).
 *
 * <p>Field nullability by status:
 *
 * <ul>
 *   <li>{@code VERIFIED} — {@code verifiedAt} populated; {@code failedReason} null
 *   <li>{@code FAILED} — {@code failedReason} populated; {@code verifiedAt} null
 *   <li>{@code PENDING} — both null (only returned in the upstream-timeout
 *       passthrough path, when the controller chooses to relay a 503; the
 *       happy-path use case never returns PENDING because it always lands
 *       a terminal state before returning)
 * </ul>
 */
public record ConfirmRealnameResult(
        String providerBizId, RealnameStatus status, FailedReason failedReason, Instant verifiedAt) {}
