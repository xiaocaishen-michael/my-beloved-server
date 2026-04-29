package com.mbw.account.domain.service;

import com.mbw.account.domain.exception.WeakPasswordException;

/**
 * Domain service enforcing FR-003 password strength requirements:
 *
 * <ul>
 *   <li>length ≥ {@link #MIN_LENGTH}
 *   <li>at least one uppercase letter
 *   <li>at least one lowercase letter
 *   <li>at least one digit
 * </ul>
 *
 * <p>Stateless utility, called before the BCrypt hash is computed in
 * {@code RegisterByPhoneUseCase}; once a {@code PasswordHash} value
 * object exists the plaintext has already been validated and dropped.
 *
 * <p>By design the failure messages disclose the failing rule but
 * <b>never</b> the submitted plaintext — see CLAUDE.md § 四 "严禁出现
 * 在日志中".
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    private PasswordPolicy() {}

    /**
     * Validate that {@code plaintext} satisfies all FR-003 rules.
     *
     * @param plaintext user-submitted password; null is rejected
     * @throws WeakPasswordException if any rule fails (with the failing
     *     rule on the message, never the password)
     */
    public static void validate(String plaintext) {
        if (plaintext == null) {
            throw new WeakPasswordException("password must not be null");
        }
        if (plaintext.length() < MIN_LENGTH) {
            throw new WeakPasswordException("password must be at least " + MIN_LENGTH + " characters");
        }
        if (plaintext.chars().noneMatch(Character::isUpperCase)) {
            throw new WeakPasswordException("password must contain at least one uppercase letter");
        }
        if (plaintext.chars().noneMatch(Character::isLowerCase)) {
            throw new WeakPasswordException("password must contain at least one lowercase letter");
        }
        if (plaintext.chars().noneMatch(Character::isDigit)) {
            throw new WeakPasswordException("password must contain at least one digit");
        }
    }
}
