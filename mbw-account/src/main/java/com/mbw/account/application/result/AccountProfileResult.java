package com.mbw.account.application.result;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.DisplayName;
import java.time.Instant;

/**
 * Use-case-level projection of an Account for the {@code /me} endpoints
 * (account-profile spec FR-001 / FR-003).
 *
 * <p>{@code displayName} is nullable — newly auto-created accounts
 * (phoneSmsAuth) leave it null until onboarding completes.
 */
public record AccountProfileResult(
        AccountId accountId, DisplayName displayName, AccountStatus status, Instant createdAt) {}
