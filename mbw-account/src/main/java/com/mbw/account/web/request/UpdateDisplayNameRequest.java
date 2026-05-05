package com.mbw.account.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * HTTP request body for {@code PATCH /api/v1/accounts/me}.
 *
 * <p>Boundary-level validation is intentionally loose ({@code @NotBlank}
 * + {@code @Size(max=64)}); the precise FR-005 rules (codepoint count,
 * forbidden character classes) live in the {@code DisplayName} value
 * object, which is constructed inside the use case so the rate-limit
 * gate runs first.
 */
public record UpdateDisplayNameRequest(@NotBlank @Size(max = 64) String displayName) {}
