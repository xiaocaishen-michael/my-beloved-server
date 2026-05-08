package com.mbw.account.application.result;

import com.mbw.account.api.dto.DeviceType;
import com.mbw.account.api.dto.LoginMethod;
import java.time.Instant;
import java.util.Objects;

/**
 * Single row of a device list result (device-management spec FR-002).
 *
 * <p>The raw {@code ipAddress} is intentionally absent per CL-002 —
 * the user-facing list only exposes the resolved {@code location}
 * label (e.g. "上海") so attackers cannot enumerate device IPs via
 * the authenticated endpoint.
 *
 * @param id            database row id, used as the path variable for
 *                      DELETE
 * @param deviceId      stable client-side UUID v4 (from
 *                      {@code refresh_token.device_id})
 * @param deviceName    nullable client-reported label
 * @param deviceType    coarse-grained device category
 * @param location      resolved Chinese province/city, {@code null}
 *                      when the row's IP is private or unresolvable
 * @param loginMethod   login mechanism that issued the row (refresh
 *                      rotations preserve the parent's value per
 *                      FR-012)
 * @param lastActiveAt  the row's {@code created_at} — proxy for "last
 *                      time this device refreshed" since rotation
 *                      replaces the row
 * @param isCurrent     {@code true} iff this row's {@code device_id}
 *                      matches the access token's {@code did} claim
 */
public record DeviceItem(
        long id,
        String deviceId,
        String deviceName,
        DeviceType deviceType,
        String location,
        LoginMethod loginMethod,
        Instant lastActiveAt,
        boolean isCurrent) {

    public DeviceItem {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(deviceType, "deviceType must not be null");
        Objects.requireNonNull(loginMethod, "loginMethod must not be null");
        Objects.requireNonNull(lastActiveAt, "lastActiveAt must not be null");
    }
}
