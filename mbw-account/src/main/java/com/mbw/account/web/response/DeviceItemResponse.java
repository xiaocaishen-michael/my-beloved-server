package com.mbw.account.web.response;

import com.mbw.account.api.dto.DeviceType;
import com.mbw.account.api.dto.LoginMethod;
import com.mbw.account.application.result.DeviceItem;
import java.time.Instant;

/**
 * HTTP wire shape for one row of the device list (device-management
 * spec FR-002).
 *
 * <p>Mirror of {@link DeviceItem} — translated 1:1 by
 * {@link #from(DeviceItem)}. The raw {@code ipAddress} is intentionally
 * absent per CL-002; only the resolved {@code location} is exposed to
 * the user-facing endpoint.
 */
public record DeviceItemResponse(
        long id,
        String deviceId,
        String deviceName,
        DeviceType deviceType,
        String location,
        LoginMethod loginMethod,
        Instant lastActiveAt,
        boolean isCurrent) {

    public static DeviceItemResponse from(DeviceItem item) {
        return new DeviceItemResponse(
                item.id(),
                item.deviceId(),
                item.deviceName(),
                item.deviceType(),
                item.location(),
                item.loginMethod(),
                item.lastActiveAt(),
                item.isCurrent());
    }
}
