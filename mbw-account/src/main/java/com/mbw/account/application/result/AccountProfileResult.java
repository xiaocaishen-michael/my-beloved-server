package com.mbw.account.application.result;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.DisplayName;
import com.mbw.account.domain.model.PhoneNumber;
import java.time.Instant;

/**
 * Use-case-level projection of an Account for the {@code /me} endpoints
 * (account-profile spec FR-001 / FR-003).
 *
 * <p>{@code phone} mirrors the aggregate's stored phone number; on the
 * {@code /me} happy path the use case has already gated on
 * {@code status == ACTIVE}, so this field is effectively non-null for
 * callers (PhoneNumber on an ANONYMIZED row is null but never reaches
 * this projection). Web layer ({@code AccountProfileResponse.from})
 * unpacks it to E.164 string for serialization.
 *
 * <p>{@code displayName} is nullable — newly auto-created accounts
 * (phoneSmsAuth) leave it null until onboarding completes.
 */
public record AccountProfileResult(
        AccountId accountId, PhoneNumber phone, DisplayName displayName, AccountStatus status, Instant createdAt) {}
