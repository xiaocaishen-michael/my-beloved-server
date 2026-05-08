package com.mbw.account.web.resolver;

import com.mbw.account.api.dto.DeviceType;
import com.mbw.account.domain.model.DeviceId;
import com.mbw.account.domain.model.DeviceName;
import java.util.Objects;

/**
 * Triplet of client-supplied device identifiers extracted from request
 * headers (device-management spec FR-009). Used by token-issuing
 * UseCases and the device-management endpoints to populate the five
 * device-metadata columns on {@code account.refresh_token}.
 *
 * <p>{@code deviceId} is always present (server falls back to a random
 * UUID when the header is missing per CL-001 (a)); {@code deviceName}
 * is nullable (no header → null column); {@code deviceType} defaults
 * to {@link DeviceType#UNKNOWN} when the header is absent or invalid.
 */
public record DeviceMetadata(DeviceId deviceId, DeviceName deviceName, DeviceType deviceType) {

    public DeviceMetadata {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(deviceType, "deviceType must not be null");
    }
}
