package com.mbw.account.domain.service;

import com.mbw.account.domain.model.PasswordHash;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Pads execution to a target wall-clock duration so success / failure
 * paths look identical to a timing-side-channel observer (FR-013).
 *
 * <p>Per {@code spec/account/register-by-phone/plan.md} § "Timing
 * Defense", a single dummy bcrypt at the entry is not enough — the
 * register flow's success branch (commit + credential INSERT, ~50ms)
 * differs from the already-registered branch (DataIntegrityViolation
 * roll-back, ~10ms) by ~40ms, which leaves zero margin against
 * SC-004's 50ms threshold. Wrapping the entire use case in
 * {@link #executeInConstantTime} pins the wall-clock to the slowest
 * realistic path and pads the rest.
 *
 * <p><b>Behaviour</b>:
 *
 * <ul>
 *   <li>Body's return value is returned verbatim.
 *   <li>Body's exception is rethrown after the pad runs (so attackers
 *       can't observe a faster failure response).
 *   <li>If body already took longer than {@code target}, no extra sleep
 *       happens. (Recovery from this scenario is operational — alerts
 *       on P95 budget — not in this class's job.)
 *   <li>If the padding sleep is interrupted, the thread's interrupt
 *       flag is restored and the original outcome (return value or
 *       exception) is preserved.
 * </ul>
 *
 * <p>Thread.sleep blocks the current worker; FR-006 rate-limit and
 * the limited register endpoint QPS keep this acceptable in M1. M2
 * high-QPS scenarios may revisit (reactor / virtual-thread executor)
 * — out of scope here.
 */
public final class TimingDefenseExecutor {

    /**
     * Pre-computed BCrypt cost-8 hash used by
     * {@link #executeWithBCryptVerify} when the
     * {@code hashSupplier} would otherwise return null (account not
     * found / no PASSWORD credential / FROZEN). The {@link PasswordHash}
     * regex validates the format. The plaintext is irrelevant — this is
     * never going to match any user-provided input; its sole purpose is
     * to keep the wall-clock cost of BCrypt verify constant across the
     * "real account" and "no such account" paths (login-by-password
     * FR-009 anti-enumeration).
     */
    public static final PasswordHash DUMMY_HASH =
            new PasswordHash("$2a$08$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy");

    private TimingDefenseExecutor() {}

    /**
     * Run {@code body}, then pad the total elapsed wall-clock time so
     * it equals {@code target} regardless of whether the body returned
     * normally or threw.
     *
     * @param target the target total duration; should be slightly above
     *     the slowest realistic happy-path latency (M1: 400ms)
     * @param body the work to time-shield; runs inside a try-finally so
     *     its exception is preserved while the pad still runs
     * @return whatever {@code body} returned
     */
    public static <T> T executeInConstantTime(Duration target, Supplier<T> body) {
        return executeInConstantTime(target, body, ex -> false);
    }

    /**
     * Run {@code body} like {@link #executeInConstantTime(Duration, Supplier)},
     * but skip the wall-clock pad if {@code bypassPad} returns {@code true}
     * for the body's thrown {@link RuntimeException}.
     *
     * <p><b>Bypass semantics</b>: callers may opt out of the pad for specific
     * exception types. Used by spec D {@code expose-frozen-account-status}
     * (FR-004 + CL-003) so the FROZEN disclosure path skips padding — the
     * disclosure already exists, padding wastes a worker without security
     * gain. ANONYMIZED + invalid-credentials paths still pad to target.
     *
     * <p>Happy path always pads (predicate is never consulted on success);
     * use the 2-arg overload if no bypass is needed. {@link Error}s
     * propagate normally and still trigger the pad — bypass is intended
     * for business-disclosure {@code RuntimeException}s only.
     *
     * @param target the target total wall-clock duration (per 2-arg overload)
     * @param body the work to time-shield
     * @param bypassPad receives the body's thrown {@code RuntimeException};
     *     return {@code true} to skip padding (disclosure / explicit-error
     *     path); return {@code false} to behave like the 2-arg overload
     * @return whatever {@code body} returned
     */
    public static <T> T executeInConstantTime(Duration target, Supplier<T> body, Predicate<Throwable> bypassPad) {
        Objects.requireNonNull(bypassPad, "bypassPad must not be null");
        long startNanos = System.nanoTime();
        boolean shouldPad = true;
        try {
            return body.get();
        } catch (RuntimeException t) {
            if (bypassPad.test(t)) {
                shouldPad = false;
            }
            throw t;
        } finally {
            if (shouldPad) {
                padRemaining(target, startNanos);
            }
        }
    }

    /**
     * Run a BCrypt verify and dispatch to the match / mismatch branches,
     * keeping the wall-clock cost identical regardless of whether the
     * hash came from a real account or a dummy fallback.
     *
     * <p>The {@code hashSupplier} contract is "always non-null": when the
     * caller cannot produce a real hash (account not found / password
     * not set / FROZEN), it must return {@link #DUMMY_HASH} so the
     * BCrypt computation runs anyway. The verify result drives the
     * branch:
     *
     * <ul>
     *   <li>matches → {@code onMatch.get()}
     *   <li>mismatches (real hash that doesn't match, or any DUMMY_HASH
     *       path because the user input never hashes to the dummy
     *       value) → {@code onMismatch.get()}
     * </ul>
     *
     * <p>Used by login-by-password (FR-009): all four failure modes
     * (wrong password / unregistered phone / no password set / FROZEN)
     * collapse into the same {@code onMismatch} response shape, so
     * neither response body nor wall-clock time leaks the failure
     * reason.
     *
     * @param hasher domain-side BCrypt wrapper
     * @param userInput plaintext password from the request
     * @param hashSupplier closes over the lookup logic; must never
     *     return null
     * @param onMatch invoked when {@code hasher.matches} returns true
     * @param onMismatch invoked otherwise; typically throws
     *     {@code InvalidCredentialsException}
     */
    public static <T> T executeWithBCryptVerify(
            PasswordHasher hasher,
            String userInput,
            Supplier<PasswordHash> hashSupplier,
            Supplier<T> onMatch,
            Supplier<T> onMismatch) {
        Objects.requireNonNull(hasher, "hasher must not be null");
        Objects.requireNonNull(userInput, "userInput must not be null");
        Objects.requireNonNull(hashSupplier, "hashSupplier must not be null");
        Objects.requireNonNull(onMatch, "onMatch must not be null");
        Objects.requireNonNull(onMismatch, "onMismatch must not be null");
        PasswordHash hash =
                Objects.requireNonNull(hashSupplier.get(), "hashSupplier must return non-null PasswordHash");
        return hasher.matches(userInput, hash) ? onMatch.get() : onMismatch.get();
    }

    private static void padRemaining(Duration target, long startNanos) {
        long elapsedNanos = System.nanoTime() - startNanos;
        long remainingNanos = target.toNanos() - elapsedNanos;
        if (remainingNanos <= 0L) {
            return;
        }
        long millis = remainingNanos / 1_000_000L;
        int nanosPart = (int) (remainingNanos % 1_000_000L);
        try {
            Thread.sleep(millis, nanosPart);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
