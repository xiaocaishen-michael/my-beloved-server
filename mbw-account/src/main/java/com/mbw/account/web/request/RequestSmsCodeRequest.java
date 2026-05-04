package com.mbw.account.web.request;

import jakarta.validation.constraints.NotBlank;

/**
 * HTTP body for {@code POST /api/v1/sms-codes}. Phone-format
 * validation runs in domain ({@code PhonePolicy}); the web layer's
 * {@code @NotBlank} only catches the absent / empty cases — making
 * Spring throw a clean {@code MethodArgumentNotValidException} before
 * the use case runs.
 *
 * <p>Per ADR-0016 + spec {@code phone-sms-auth/spec.md} FR-004: the
 * {@code purpose} field has been removed — server sends a single
 * Template A real verification code regardless of phone existence.
 */
public record RequestSmsCodeRequest(@NotBlank String phone) {}
