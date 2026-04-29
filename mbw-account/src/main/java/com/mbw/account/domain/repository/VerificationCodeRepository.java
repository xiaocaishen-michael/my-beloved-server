package com.mbw.account.domain.repository;

import com.mbw.account.domain.model.PhoneNumber;
import java.time.Duration;
import java.util.Optional;

/**
 * Domain-side persistence contract for the SMS verification-code store.
 *
 * <p>Implemented by Redis-backed adapter
 * ({@code RedisVerificationCodeRepository} in T10) using SETNX +
 * Lua-scripted atomic counter (see {@code plan.md} § "infrastructure
 * 实现要点"). Domain layer treats the stored hash as an opaque {@code
 * String} — the repository never sees plaintext, and never wraps the
 * hash in a value object since it is implementation noise rather than
 * a domain concept (deviation from {@code plan.md} which loosely typed
 * this as {@code Optional<VerificationCode>}; that would conflate the
 * 6-digit-validating plaintext value object with stored bcrypt hash).
 */
public interface VerificationCodeRepository {

    /**
     * Store a verification-code hash for {@code phone}, only if no
     * entry exists. Atomic via Redis {@code SET NX EX} so concurrent
     * "request code" requests within the rate-limit window do not
     * overwrite each other.
     *
     * @return true if stored; false if an entry already existed
     */
    boolean storeIfAbsent(PhoneNumber phone, String codeHash, Duration ttl);

    /**
     * @return the stored hash if a non-expired entry exists for the
     *     phone; never the plaintext code (which only exists in
     *     transit over SMS and in the user's input)
     */
    Optional<String> findHashByPhone(PhoneNumber phone);

    /**
     * Atomically increment the failed-attempt counter and, if the new
     * count reaches {@code maxAttempts}, delete the entry within the
     * same Redis round-trip (Lua-scripted in the impl). Returns the
     * resulting count and whether the entry was invalidated.
     */
    AttemptOutcome incrementAttemptOrInvalidate(PhoneNumber phone, int maxAttempts);

    /** Verification succeeded — consume the code. */
    void delete(PhoneNumber phone);

    /**
     * Outcome of {@link #incrementAttemptOrInvalidate}.
     *
     * @param count the post-increment failure count
     * @param invalidated whether the entry was deleted because count
     *     reached the threshold
     */
    record AttemptOutcome(int count, boolean invalidated) {

        public AttemptOutcome {
            if (count < 0) {
                throw new IllegalArgumentException("count must be non-negative, got " + count);
            }
        }
    }
}
