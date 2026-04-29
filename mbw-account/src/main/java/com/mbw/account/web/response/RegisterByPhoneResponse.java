package com.mbw.account.web.response;

/**
 * HTTP response body for {@code POST /api/v1/accounts/register-by-phone}.
 * Mirrors {@code RegisterByPhoneResult} from the application layer; the
 * separation lets the web view evolve without leaking through to use
 * case contracts (e.g. additional fields like {@code expiresIn} could
 * be added here without touching the use case).
 */
public record RegisterByPhoneResponse(long accountId, String accessToken, String refreshToken) {}
