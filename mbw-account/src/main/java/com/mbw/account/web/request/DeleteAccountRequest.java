package com.mbw.account.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * HTTP body for {@code POST /api/v1/accounts/me/deletion}.
 *
 * <p>The 6-digit pattern is the only web-layer guard; cryptographic hash
 * comparison runs inside {@code DeleteAccountUseCase}. {@code @NotBlank}
 * catches absent / empty values before the pattern check.
 */
public record DeleteAccountRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {}
