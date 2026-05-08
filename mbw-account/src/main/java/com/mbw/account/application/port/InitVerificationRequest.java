package com.mbw.account.application.port;

/**
 * Initiate-verification request DTO carrying server-generated
 * {@code providerBizId} (UUID v4 per plan D-003) plus plaintext PII required
 * by the upstream API.
 *
 * <p><b>Lifetime</b>: this DTO must <b>not</b> be cached, logged, or
 * serialised — the plaintext fields are only legitimate inside the
 * {@code InitiateRealnameVerificationUseCase} transaction, between cipher
 * decrypt (caller) and HTTPS dispatch (provider impl).
 *
 * @param providerBizId server-allocated UUID, also persisted to
 *     {@code realname_profile.provider_biz_id}
 * @param realName plaintext real name (Han characters)
 * @param idCardNo plaintext 18-digit GB 11643 ID card number
 */
public record InitVerificationRequest(String providerBizId, String realName, String idCardNo) {}
