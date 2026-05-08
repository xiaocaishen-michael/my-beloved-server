package com.mbw.account.api.dto;

/**
 * Authentication mechanism that issued the {@code refresh_token} row
 * (device-management spec FR-007).
 *
 * <p>The {@code REFRESH} value is intentionally absent — per
 * {@code FR-012} a token rotation inherits its parent row's
 * {@code login_method}, preserving the "first login method" semantic
 * across the rotation chain. Adding a {@code REFRESH} value would mask
 * that lineage.
 *
 * <p>{@link #GOOGLE} / {@link #APPLE} / {@link #WECHAT} are reserved for
 * future OAuth use cases; only {@link #PHONE_SMS} is wired in M1.
 */
public enum LoginMethod {
    PHONE_SMS,
    GOOGLE,
    APPLE,
    WECHAT
}
