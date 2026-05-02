package com.mbw.account.application.result;

/**
 * Output of {@code LoginByPasswordUseCase}: the account id plus an
 * access/refresh token pair (FR-002). Web layer maps to
 * {@code LoginResponse} (shared with login-by-phone-sms).
 */
public record LoginByPasswordResult(long accountId, String accessToken, String refreshToken) {}
