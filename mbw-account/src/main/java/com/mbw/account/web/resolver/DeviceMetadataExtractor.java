package com.mbw.account.web.resolver;

import com.mbw.account.api.dto.DeviceType;
import com.mbw.account.domain.model.DeviceId;
import com.mbw.account.domain.model.DeviceName;
import com.mbw.account.domain.model.IpAddress;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Static helpers that translate raw {@link HttpServletRequest} headers
 * into the device-management domain types
 * (device-management spec FR-009 / FR-010).
 *
 * <p>{@link #extractIp} walks {@code X-Forwarded-For} left-to-right
 * picking the first segment that parses as a public IP literal, falling
 * back to {@link HttpServletRequest#getRemoteAddr()}; private,
 * loopback, and link-local addresses are filtered to {@code null} so a
 * row inserted from inside the trust boundary never persists a private
 * IP.
 *
 * <p>{@link #extractDeviceMetadata} reads the three {@code X-Device-*}
 * headers and folds bad / missing values to the documented defaults
 * (random UUID for the id, {@code null} for the name,
 * {@link DeviceType#UNKNOWN} for the type) so a degraded client cannot
 * derail an otherwise-valid request.
 */
public final class DeviceMetadataExtractor {

    static final String XFF_HEADER = "X-Forwarded-For";
    static final String DEVICE_ID_HEADER = "X-Device-Id";
    static final String DEVICE_NAME_HEADER = "X-Device-Name";
    static final String DEVICE_TYPE_HEADER = "X-Device-Type";

    private DeviceMetadataExtractor() {}

    /** Best-effort public IP for a request, or {@code null} when none can be determined. */
    public static String extractIp(HttpServletRequest req) {
        String xff = req.getHeader(XFF_HEADER);
        if (xff != null && !xff.isBlank()) {
            for (String segment : xff.split(",")) {
                String candidate = pickPublic(segment.trim());
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return pickPublic(req.getRemoteAddr());
    }

    /** Read X-Device-* headers into a {@link DeviceMetadata} triplet, with documented degradation defaults. */
    public static DeviceMetadata extractDeviceMetadata(HttpServletRequest req) {
        DeviceId deviceId = DeviceId.fromHeaderOrFallback(req.getHeader(DEVICE_ID_HEADER));
        DeviceName deviceName = safeDeviceName(req.getHeader(DEVICE_NAME_HEADER));
        DeviceType deviceType = parseDeviceType(req.getHeader(DEVICE_TYPE_HEADER));
        return new DeviceMetadata(deviceId, deviceName, deviceType);
    }

    private static String pickPublic(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        IpAddress ip = IpAddress.ofNullable(raw);
        if (ip == null || ip.isPrivate()) {
            return null;
        }
        return ip.value();
    }

    private static DeviceName safeDeviceName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DeviceName.ofNullable(raw);
        } catch (IllegalArgumentException e) {
            // Oversized / malformed header — degrade to null rather than fail the request.
            return null;
        }
    }

    private static DeviceType parseDeviceType(String raw) {
        if (raw == null || raw.isBlank()) {
            return DeviceType.UNKNOWN;
        }
        try {
            return DeviceType.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return DeviceType.UNKNOWN;
        }
    }
}
