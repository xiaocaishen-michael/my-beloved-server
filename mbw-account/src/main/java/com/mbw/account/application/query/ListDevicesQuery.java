package com.mbw.account.application.query;

import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.DeviceId;
import java.util.Objects;

/**
 * Input for {@code ListDevicesUseCase} (device-management spec
 * FR-001 / FR-013).
 *
 * <p>{@code currentDeviceId} comes from the access token's {@code did}
 * claim — used to flag which {@code DeviceItem} represents this very
 * request (FR-004). {@code page} / {@code size} are passed through
 * from the controller; the use case applies the FR-013 size clamp at
 * 100. {@code clientIp} feeds the IP-dimension rate-limit bucket; may
 * be {@code null} when the request originates entirely from private
 * ranges.
 */
public record ListDevicesQuery(AccountId accountId, DeviceId currentDeviceId, int page, int size, String clientIp) {

    public ListDevicesQuery {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(currentDeviceId, "currentDeviceId must not be null");
        if (page < 0) {
            throw new IllegalArgumentException("page must be non-negative, got " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive, got " + size);
        }
    }
}
