package com.mbw.account.domain.service;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.DeviceId;
import java.util.Optional;

/**
 * Domain-side abstraction for issuing post-authentication tokens
 * (FR-008 + device-management spec FR-006 / FR-008).
 *
 * <p>{@link #signAccess(AccountId, DeviceId)} produces a JWT carrying
 * the account id as {@code sub} and the device id as the
 * {@code did} custom claim; clients send it on protected requests.
 * {@link #signRefresh} returns an opaque 256-bit random string; the
 * server stores its hash and rotates a fresh one on every refresh.
 *
 * <p>The split (signed JWT vs opaque random) is per spec FR-008:
 * access tokens are stateless / verifiable client-side; refresh tokens
 * are revocable (server holds the canonical state) and don't need to
 * be JWT.
 */
public interface TokenIssuer {

    /**
     * @return signed JWT, TTL 15min, claims {@code sub=accountId} +
     *     {@code did=deviceId}
     */
    String signAccess(AccountId accountId, DeviceId deviceId);

    /** @return URL-safe base64-encoded 256-bit random opaque token */
    String signRefresh();

    /**
     * Verify a JWT access token and extract both {@code sub} and
     * {@code did} claims. Returns empty when the token is malformed,
     * signed with a different secret, expired, or **missing the did
     * claim** (per device-management spec FR-006 — old-format tokens
     * issued before the upgrade must force a re-login). Used by the
     * device-management endpoints to authenticate the requester and
     * identify their current device in one parse.
     */
    Optional<AuthenticatedTokenClaims> verifyAccessWithDevice(String token);

    /**
     * Legacy single-arg form used by Phase 1.4 logout-all to
     * authenticate the requester without a full Spring Security filter
     * chain. Issues an access token with no {@code did} claim.
     *
     * @deprecated migrate token-issuing UseCases to
     *     {@link #signAccess(AccountId, DeviceId)} as part of T9; this
     *     overload remains until the cross-spec wiring lands.
     */
    @Deprecated
    String signAccess(AccountId accountId);

    /**
     * Legacy verifier returning only the {@code sub} claim. Tolerates
     * tokens without {@code did} (used by logout-all today). New
     * code should call {@link #verifyAccessWithDevice} instead.
     *
     * @deprecated migrate consumers to
     *     {@link #verifyAccessWithDevice} once the device-aware
     *     endpoints land; logout-all keeps this form to support old
     *     tokens during the rollout window.
     */
    @Deprecated
    Optional<AccountId> verifyAccess(String token);
}
