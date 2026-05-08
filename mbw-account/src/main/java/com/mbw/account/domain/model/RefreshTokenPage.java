package com.mbw.account.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Pure-domain page slice over {@link RefreshTokenRecord} (device-management
 * spec FR-001). Returned by {@code RefreshTokenRepository.findActiveByAccountId}
 * so the application layer can paginate without depending on Spring Data
 * (per modular-strategy.md § Repository pattern).
 *
 * <p>{@link #totalElements} is the population size across all pages;
 * pagination math (totalPages, hasNext) lives at the application/web
 * layer where the request {@code size} is known.
 */
public record RefreshTokenPage(List<RefreshTokenRecord> items, long totalElements) {

    public RefreshTokenPage {
        Objects.requireNonNull(items, "items must not be null");
        if (totalElements < 0L) {
            throw new IllegalArgumentException("totalElements must be non-negative, got " + totalElements);
        }
        items = List.copyOf(items);
    }
}
