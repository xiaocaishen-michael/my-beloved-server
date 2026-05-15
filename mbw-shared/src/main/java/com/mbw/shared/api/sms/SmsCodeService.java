package com.mbw.shared.api.sms;

/**
 * Cross-module SMS verification-code service.
 *
 * <p>Encapsulates the full lifecycle of a one-time verification code:
 * generate-and-store on send, then verify on user submission. Business
 * modules ({@code mbw-account.application.RequestSmsCodeUseCase} /
 * {@code RegisterByPhoneUseCase}) depend on this contract; the concrete
 * Redis-backed implementation ({@code RedisSmsCodeService}, T2b) lives
 * in {@code mbw-app/infrastructure/sms} per
 * {@code specs/auth/register-by-phone/plan.md} § "SmsCodeService 跨模块归属".
 *
 * <p>Implementations are responsible for:
 *
 * <ul>
 *   <li>generating a 6-digit code, BCrypt-hashing it (cost 8), and
 *       persisting with TTL (M1.1 = 5 minutes per spec)
 *   <li>verifying user-submitted code via constant-time hash comparison
 *   <li>tracking failed-attempt count atomically (Lua script in Redis)
 *       so {@link AttemptOutcome#attempts()} is consistent under
 *       concurrent verify calls for the same phone
 *   <li>invalidating the code after {@code maxAttempts} failures (default
 *       3) — exposed via {@link AttemptOutcome#invalidated()}
 * </ul>
 */
public interface SmsCodeService {

    /**
     * Generate a fresh verification code for the given phone, persist
     * its hash with TTL, and return the plaintext code so the caller
     * can deliver it via {@link SmsClient}.
     *
     * <p>Splitting generation from delivery lets
     * {@code RequestSmsCodeUseCase} swap SMS templates per FR-012
     * (Template A vs B) without touching storage logic.
     *
     * <p>Implementations may throw if a non-expired entry already
     * exists for the phone; rate limiting upstream of this call
     * (FR-006 60s window) is the primary guard against that race.
     *
     * @param phone E.164-formatted phone number
     * @return plaintext 6-digit verification code (never logged in
     *     production per CLAUDE.md § 四 log-safety)
     */
    String generateAndStore(String phone);

    /**
     * Verify a user-submitted code against the stored hash.
     *
     * <p>On success: the stored code is consumed (deleted) — subsequent
     * calls for the same phone return a not-found-style failure outcome.
     * On failure: the per-code attempt counter is incremented atomically;
     * once the configured {@code maxAttempts} threshold (default 3) is
     * crossed the code is permanently invalidated within its TTL window.
     *
     * @param phone E.164-formatted phone number
     * @param code plaintext 6-digit code submitted by the user
     * @return outcome describing success / attempts so far / invalidation
     */
    AttemptOutcome verify(String phone, String code);
}
