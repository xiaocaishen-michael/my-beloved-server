package com.mbw.account.domain.service;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.DeviceId;
import java.util.Objects;

/**
 * Verified access-token principal carrying both the subject account
 * and the device that token was issued for (device-management spec
 * FR-006 / FR-008).
 *
 * <p>Returned by {@link TokenIssuer#verifyAccessWithDevice} only when
 * the token's signature, expiry, {@code sub}, and {@code did} claims
 * all check out. Tokens missing the {@code did} claim are rejected at
 * the issuer level so this record cannot represent a "device-less"
 * principal — that condition surfaces as {@link java.util.Optional#empty()}.
 */
public record AuthenticatedTokenClaims(AccountId accountId, DeviceId deviceId) {

    public AuthenticatedTokenClaims {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(deviceId, "deviceId must not be null");
    }
}
