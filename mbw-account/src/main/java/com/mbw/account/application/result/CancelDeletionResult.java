package com.mbw.account.application.result;

/**
 * Output of {@code CancelDeletionUseCase} — same shape as
 * {@link PhoneSmsAuthResult} / {@code LoginResponse} so the web layer
 * can reuse {@code LoginResponse} for the cancel-deletion endpoint
 * (cancel-deletion spec § Endpoint 2 response).
 */
public record CancelDeletionResult(long accountId, String accessToken, String refreshToken) {}
