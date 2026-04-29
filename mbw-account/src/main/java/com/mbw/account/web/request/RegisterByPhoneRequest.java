package com.mbw.account.web.request;

import jakarta.validation.constraints.NotBlank;

/**
 * HTTP body for {@code POST /api/v1/accounts/register-by-phone}.
 * Password is optional per FR-003; absent / null on the wire becomes
 * {@code null} which the use case maps to {@code Optional.empty()}.
 */
public record RegisterByPhoneRequest(@NotBlank String phone, @NotBlank String code, String password) {}
