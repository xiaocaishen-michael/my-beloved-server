package com.mbw.account.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * HTTP body for {@code POST /api/v1/auth/cancel-deletion/sms-codes}.
 *
 * <p>Generic E.164 pattern is the web-layer guard; mainland-only
 * validation runs inside the use case via {@code PhonePolicy.validate}
 * (which throws {@code InvalidPhoneFormatException} → 422 for
 * out-of-region phones). {@code @NotBlank} catches absent / empty
 * values before the pattern check.
 */
public record SendCancelDeletionCodeRequest(@NotBlank @Pattern(regexp = "^\\+\\d{8,15}$") String phone) {}
