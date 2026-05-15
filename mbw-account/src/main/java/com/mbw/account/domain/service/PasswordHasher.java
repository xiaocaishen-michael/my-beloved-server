package com.mbw.account.domain.service;

import com.mbw.account.domain.model.PasswordHash;

/**
 * Domain-side abstraction for password hashing. The implementation
 * ({@code BCryptPasswordHasher} in {@code infrastructure.security})
 * uses BCrypt cost 8 per {@code specs/auth/register-by-phone/plan.md}.
 *
 * <p>Use cases call {@link #hash} before constructing
 * {@code PasswordCredential}; {@link #matches} powers verification on
 * future login flows.
 */
public interface PasswordHasher {

    /**
     * Hash a plaintext password with the configured BCrypt cost.
     *
     * @param plaintext password the user submitted, already validated
     *     by {@code PasswordPolicy}
     * @return wrapped BCrypt output
     */
    PasswordHash hash(String plaintext);

    /**
     * Constant-time comparison of plaintext against a stored hash.
     *
     * @return true if the plaintext hashes to the stored value
     */
    boolean matches(String plaintext, PasswordHash hash);
}
