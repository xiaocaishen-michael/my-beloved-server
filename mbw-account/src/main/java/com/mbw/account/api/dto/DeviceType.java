package com.mbw.account.api.dto;

/**
 * Coarse-grained device category persisted on each {@code refresh_token}
 * row (device-management spec FR-007). Drives UI icon selection and
 * client-server reconciliation when no fingerprint hint is supplied.
 *
 * <p>Placed in {@code api.dto} so consumers in other modules (e.g. an
 * audit subscriber on {@code DeviceRevokedEvent}) can read the value
 * without depending on internal account types.
 */
public enum DeviceType {
    PHONE,
    TABLET,
    DESKTOP,
    WEB,
    UNKNOWN
}
