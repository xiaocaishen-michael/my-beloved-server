package com.mbw.account.web.response;

import com.mbw.account.application.result.InitiateRealnameResult;

/**
 * POST /api/v1/realname/verifications response body
 * (realname-verification spec T16, plan.md § Web).
 */
public record RealnameInitiateResponse(String providerBizId, String livenessUrl) {

    public static RealnameInitiateResponse from(InitiateRealnameResult result) {
        return new RealnameInitiateResponse(result.providerBizId(), result.livenessUrl());
    }
}
