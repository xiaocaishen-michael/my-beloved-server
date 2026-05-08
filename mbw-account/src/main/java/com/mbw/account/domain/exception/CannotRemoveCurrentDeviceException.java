package com.mbw.account.domain.exception;

/**
 * Thrown when a {@code DELETE /api/v1/auth/devices/{recordId}} request
 * targets the row whose {@code device_id} matches the caller's access
 * token {@code did} claim (device-management spec FR-005).
 *
 * <p>Web advice maps this to HTTP 409 with code
 * {@code CANNOT_REMOVE_CURRENT_DEVICE} and the user-facing message
 * "当前设备请通过『退出登录』移除" — directing the user to
 * {@code logout-all} (or a future single-device logout) rather than
 * silently revoking their own session.
 */
public class CannotRemoveCurrentDeviceException extends RuntimeException {

    public CannotRemoveCurrentDeviceException() {
        super("CANNOT_REMOVE_CURRENT_DEVICE");
    }
}
