package com.mbw.account.web.response;

import com.mbw.account.application.result.ConfirmRealnameResult;
import com.mbw.account.domain.model.FailedReason;
import com.mbw.account.domain.model.RealnameStatus;
import java.time.Instant;

/**
 * GET /api/v1/realname/verifications/{providerBizId} response body
 * (realname-verification spec T16, plan.md § Web).
 */
public record RealnameConfirmResponse(
        String providerBizId, RealnameStatus status, FailedReason failedReason, Instant verifiedAt) {

    public static RealnameConfirmResponse from(ConfirmRealnameResult result) {
        return new RealnameConfirmResponse(
                result.providerBizId(), result.status(), result.failedReason(), result.verifiedAt());
    }
}
