package com.mbw.account.domain.service;

import java.time.Duration;
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
        long startNanos = System.nanoTime();
        try {
            return body.get();
        } finally {
            padRemaining(target, startNanos);
        }
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
