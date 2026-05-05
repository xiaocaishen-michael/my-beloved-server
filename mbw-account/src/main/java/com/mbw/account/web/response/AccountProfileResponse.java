package com.mbw.account.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mbw.account.application.result.AccountProfileResult;
import java.time.Instant;

/**
 * HTTP response body for {@code GET / PATCH /api/v1/accounts/me}.
 *
 * <p>{@code displayName} is null until onboarding completes — front-end
 * AuthGate reads it as the unique signal to route to the onboarding
 * flow (account-profile spec FR-001 / CL-001).
 *
 * <p>Spec FR-001 calls out exactly four fields: {@code accountId},
 * {@code displayName}, {@code status}, {@code createdAt}; deliberately
 * narrow so the byte shape stays stable across releases.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AccountProfileResponse(Long accountId, String displayName, String status, Instant createdAt) {

    public static AccountProfileResponse from(AccountProfileResult result) {
        return new AccountProfileResponse(
                result.accountId().value(),
                result.displayName() == null ? null : result.displayName().value(),
                result.status().name(),
                result.createdAt());
    }
}
