package com.mbw.account.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * HTTP body for {@code POST /api/v1/accounts/phone-sms-auth} (per
 * ADR-0016 unified mobile-first phone-SMS auth + spec
 * {@code phone-sms-auth/spec.md} FR-001).
 *
 * <p>Phone-format validation runs in domain ({@code PhonePolicy});
 * the web layer's {@code @NotBlank} only catches the absent / empty
 * cases. Code format is validated here ({@code @Pattern} for 6 digits)
 * to reject malformed payloads early without a use case round-trip.
 */
public record PhoneSmsAuthRequest(@NotBlank String phone, @NotBlank @Pattern(regexp = "^\\d{6}$") String code) {}
