package com.mbw.account.domain.service;

import com.mbw.account.domain.exception.InvalidPhoneFormatException;
import com.mbw.account.domain.model.PhoneNumber;

/**
 * Domain service that gates conversion of raw phone strings into the
 * {@link PhoneNumber} value object (FR-001).
 *
 * <p>Two responsibilities encapsulated here that don't belong on the
 * value object:
 *
 * <ol>
 *   <li>convert {@link IllegalArgumentException} (the value object's
 *       contract-violation signal) into the domain-specific
 *       {@link InvalidPhoneFormatException}, so application/web layers
 *       map a single exception type to {@code INVALID_PHONE_FORMAT}
 *   <li>preserve the submitted-but-invalid string on the exception so
 *       structured logs can record it (with PII handling left to the
 *       logger configuration, never written through this layer)
 * </ol>
 *
 * <p>Stateless utility — exposed via a single static method to match
 * the call style in {@code RequestSmsCodeUseCase} /
 * {@code RegisterByPhoneUseCase} pseudocode.
 */
public final class PhonePolicy {

    private PhonePolicy() {}

    /**
     * Validate a raw phone string and return the corresponding
     * {@link PhoneNumber} value object.
     *
     * @param e164 user-submitted phone, expected to be E.164 with the
     *     mainland-China prefix; null is rejected
     * @return validated {@link PhoneNumber}
     * @throws InvalidPhoneFormatException if the format violates FR-001
     */
    public static PhoneNumber validate(String e164) {
        if (e164 == null) {
            throw new InvalidPhoneFormatException(null);
        }
        try {
            return new PhoneNumber(e164);
        } catch (IllegalArgumentException ex) {
            throw new InvalidPhoneFormatException(e164);
        }
    }
}
