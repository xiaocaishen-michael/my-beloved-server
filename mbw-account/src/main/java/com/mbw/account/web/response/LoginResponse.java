package com.mbw.account.web.response;

/**
 * HTTP response body for the auth endpoints (login-by-phone-sms in
 * Phase 1.1; login-by-password / refresh-token / logout-all in
 * subsequent phases will reuse this shape per spec consistency).
 *
 * <p>Mirrors {@code LoginByPhoneSmsResult} from the application layer.
 * The field names match the front-end {@code @nvy/auth} Zustand store
 * (per cross-repo api-contract.md) so OpenAPI codegen produces
 * directly-usable types.
 */
public record LoginResponse(long accountId, String accessToken, String refreshToken) {}
