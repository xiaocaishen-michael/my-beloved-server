package com.mbw.account.application.result;

/**
 * Output of {@code LoginByPhoneSmsUseCase}: the account id plus an
 * access/refresh token pair (FR-007). Web layer maps to the HTTP
 * response body (LoginResponse).
 */
public record LoginByPhoneSmsResult(long accountId, String accessToken, String refreshToken) {}
