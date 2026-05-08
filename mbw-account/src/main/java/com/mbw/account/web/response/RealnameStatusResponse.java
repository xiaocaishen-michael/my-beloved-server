package com.mbw.account.web.response;

import com.mbw.account.application.result.RealnameStatusResult;
import com.mbw.account.domain.model.FailedReason;
import com.mbw.account.domain.model.RealnameStatus;
import java.time.Instant;

/**
 * GET /api/v1/realname/me response body
 * (realname-verification spec T16, plan.md § Web). Mirrors
 * {@link RealnameStatusResult} 1:1 — never includes plaintext name / id card.
 */
public record RealnameStatusResponse(
        RealnameStatus status,
        String realNameMasked,
        String idCardMasked,
        Instant verifiedAt,
        FailedReason failedReason) {

    public static RealnameStatusResponse from(RealnameStatusResult result) {
        return new RealnameStatusResponse(
                result.status(),
                result.realNameMasked(),
                result.idCardMasked(),
                result.verifiedAt(),
                result.failedReason());
    }
}
