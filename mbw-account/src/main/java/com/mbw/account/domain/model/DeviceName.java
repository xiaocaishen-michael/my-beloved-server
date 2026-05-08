package com.mbw.account.domain.model;

import java.util.Objects;

/**
 * Client-reported device label persisted on each {@code refresh_token}
 * row (device-management spec FR-007 / FR-009).
 *
 * <p>Native clients send {@code expo-device.deviceName} (e.g.
 * "MK-iPhone"); web clients fall back to a coarse server-side
 * User-Agent parse. The 64-char ceiling matches the column width and
 * comfortably accommodates Chinese names plus an emoji.
 *
 * <p>The canonical constructor enforces invariants for non-null input.
 * Use {@link #ofNullable} when the source is the optional
 * {@code X-Device-Name} header — null / blank input collapses to
 * {@code null} (DB column is nullable) rather than throwing.
 */
public record DeviceName(String value) {

    private static final int MAX_LENGTH = 64;

    public DeviceName {
        Objects.requireNonNull(value, "deviceName must not be null");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("INVALID_DEVICE_NAME: empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("INVALID_DEVICE_NAME: exceeds " + MAX_LENGTH + " chars");
        }
    }

    /**
     * Wrap an optional raw value (typically the {@code X-Device-Name}
     * header). Returns {@code null} when the input is {@code null} or
     * blank, leaving column persistence to write {@code NULL}.
     */
    public static DeviceName ofNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return new DeviceName(raw);
    }
}
