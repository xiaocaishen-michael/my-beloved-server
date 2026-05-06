package com.mbw.account.domain.model;

/**
 * Discriminator for PG-backed SMS verification codes stored in
 * {@code account.account_sms_code} (V8 migration).
 *
 * <p>Each value maps to a distinct code lifecycle: codes of different
 * purposes are physically isolated so a DELETE_ACCOUNT code can never
 * satisfy a PHONE_SMS_AUTH lookup (FR-007 / delete-account CL-005).
 *
 * <p>Persisted as VARCHAR via
 * {@code @Enumerated(EnumType.STRING)} — never the ordinal.
 */
public enum AccountSmsCodePurpose {
    PHONE_SMS_AUTH,
    DELETE_ACCOUNT,
    CANCEL_DELETION,
}
