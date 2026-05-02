package com.mbw.account.application.result;

/**
 * Output of {@code LogoutAllSessionsUseCase} (Phase 1.4) — exposes only
 * the affected-row count for application-side logging, not for the HTTP
 * response (which is 204 No Content per FR-002).
 */
public record LogoutAllSessionsResult(int revokedCount) {}
