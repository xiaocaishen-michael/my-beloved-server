package com.mbw.account.domain.exception;

/**
 * Thrown when a {@code DELETE /api/v1/auth/devices/{recordId}} request
 * targets a row that either does not exist or belongs to another
 * account (device-management spec FR-014, anti-enumeration: byte-level
 * identical 404 in both branches).
 *
 * <p>Web advice maps this to HTTP 404 with code
 * {@code DEVICE_NOT_FOUND}. Constant-time padding is unnecessary —
 * the surrounding rate limit is the disclosure-bounding control.
 */
public class DeviceNotFoundException extends RuntimeException {

    public DeviceNotFoundException() {
        super("DEVICE_NOT_FOUND");
    }
}
