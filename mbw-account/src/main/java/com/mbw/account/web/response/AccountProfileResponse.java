package com.mbw.account.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mbw.account.application.result.AccountProfileResult;
import java.time.Instant;

/**
 * HTTP response body for {@code GET / PATCH /api/v1/accounts/me}.
 *
 * <p>Carries five fields: {@code accountId}, {@code phone},
 * {@code displayName}, {@code status}, {@code createdAt}. {@code phone}
 * is the caller's E.164 number — surfaced for the account-settings-shell
 * use case (apps/native/spec/account-settings-shell/), which displays a
 * masked variant ({@code +86 138****5678}) on the 账号与安全 detail page.
 * Earlier "deliberately narrow four fields" stance is superseded
 * 2026-05-07 (this PR).
 *
 * <p>{@code displayName} is null until onboarding completes — front-end
 * AuthGate reads it as the unique signal to route to the onboarding
 * flow (account-profile spec FR-001 / CL-001).
 *
 * <p>{@code phone} is effectively non-null on the {@code /me} happy
 * path because the use case rejects non-ACTIVE accounts upstream
 * (account-profile FR-009); only ANONYMIZED rows hold null phones and
 * those are gated to 401 before reaching this projection.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AccountProfileResponse(
        Long accountId, String phone, String displayName, String status, Instant createdAt) {

    public static AccountProfileResponse from(AccountProfileResult result) {
        return new AccountProfileResponse(
                result.accountId().value(),
                result.phone() == null ? null : result.phone().e164(),
                result.displayName() == null ? null : result.displayName().value(),
                result.status().name(),
                result.createdAt());
    }
}
