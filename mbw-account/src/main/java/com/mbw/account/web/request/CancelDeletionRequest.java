package com.mbw.account.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * HTTP body for {@code POST /api/v1/auth/cancel-deletion}.
 *
 * <p>Web-layer guards: E.164 generic pattern + 6-digit code regex.
 * Cryptographic hash compare runs inside {@code CancelDeletionUseCase}.
 */
public record CancelDeletionRequest(
        @NotBlank @Pattern(regexp = "^\\+\\d{8,15}$") String phone,
        @NotBlank @Pattern(regexp = "\\d{6}") String code) {}
