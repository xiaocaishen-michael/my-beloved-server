package com.mbw.account.application.result;

/**
 * Output of {@code RegisterByPhoneUseCase}: the freshly generated
 * account id plus an access/refresh token pair (FR-008). The web
 * layer (T15) maps this to the HTTP response body.
 */
public record RegisterByPhoneResult(long accountId, String accessToken, String refreshToken) {}
