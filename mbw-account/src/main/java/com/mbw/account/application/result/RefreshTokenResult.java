package com.mbw.account.application.result;

/**
 * Output of {@code RefreshTokenUseCase} (Phase 1.3) — same shape as the
 * other auth use cases so {@code LoginResponse} can be reused at the
 * web layer.
 */
public record RefreshTokenResult(long accountId, String accessToken, String refreshToken) {}
