package com.mbw.account.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * HTTP body for {@code POST /api/v1/auth/login-by-phone-sms}.
 *
 * <p>Phone-format validation runs in domain ({@code PhonePolicy});
 * the web layer's {@code @NotBlank} only catches the absent / empty
 * cases. The {@code @Pattern} on {@code code} catches the obvious
 * "non-6-digit" submissions before the use case spends a SMS verify
 * roundtrip on them.
 */
public record LoginByPhoneSmsRequest(@NotBlank String phone, @NotBlank @Pattern(regexp = "^\\d{6}$") String code) {}
