package com.mbw.account.application.result;

/**
 * Output of {@code UnifiedPhoneSmsAuthUseCase} (per ADR-0016) — same
 * shape as {@code RefreshTokenResult} / {@code LoginResponse} so the
 * web layer can reuse {@code LoginResponse}.
 *
 * <p>Client-side cannot distinguish whether the account was newly
 * created (register branch) or pre-existed (login branch) from this
 * payload — anti-enumeration consistent response per
 * {@code phone-sms-auth/spec.md} FR-006.
 */
public record PhoneSmsAuthResult(long accountId, String accessToken, String refreshToken) {}
