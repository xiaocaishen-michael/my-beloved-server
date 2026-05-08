package com.mbw.account.api.service;

/**
 * Cross-module read-only contract for realname-verification status
 * (realname-verification spec T15).
 *
 * <p>Sibling modules (e.g. {@code mbw-billing} when M2 lands) consume this
 * interface to gate features behind realname verification — they MUST NOT
 * reach into {@code mbw-account}'s domain or repository directly. Implemented
 * by {@code IdentityApiImpl} in {@code application.service}, autowired by type.
 */
public interface IdentityApi {

    /**
     * @return true iff the account has a {@code RealnameProfile} row with
     *     status {@code VERIFIED}; false for UNVERIFIED, PENDING, FAILED, or
     *     missing profile.
     */
    boolean isVerified(long accountId);
}
