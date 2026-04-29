package com.mbw.account.domain.model;

/**
 * Account lifecycle states (FR-004 + meta requirement
 * {@code account-center.v2.md}).
 *
 * <p>M1.1 only writes {@link #ACTIVE} (no PENDING_VERIFY intermediate
 * per FR-004). {@link #FROZEN} and {@link #ANONYMIZED} are reserved for
 * follow-up use cases (account freeze, GDPR-style anonymization).
 * Persisted as VARCHAR — never the ordinal — per project DB conventions.
 */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    ANONYMIZED,
}
