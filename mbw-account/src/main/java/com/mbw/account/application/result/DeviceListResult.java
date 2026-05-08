package com.mbw.account.application.result;

import java.util.List;
import java.util.Objects;

/**
 * Paginated device-list result returned by {@code ListDevicesUseCase}
 * (device-management spec FR-001).
 *
 * <p>{@code totalPages} is precomputed from {@code totalElements} and
 * the requested {@code size} so the controller can copy the four
 * pagination fields verbatim into the HTTP response.
 */
public record DeviceListResult(int page, int size, long totalElements, int totalPages, List<DeviceItem> items) {

    public DeviceListResult {
        Objects.requireNonNull(items, "items must not be null");
        if (page < 0) {
            throw new IllegalArgumentException("page must be non-negative, got " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive, got " + size);
        }
        if (totalElements < 0L) {
            throw new IllegalArgumentException("totalElements must be non-negative, got " + totalElements);
        }
        if (totalPages < 0) {
            throw new IllegalArgumentException("totalPages must be non-negative, got " + totalPages);
        }
        items = List.copyOf(items);
    }
}
