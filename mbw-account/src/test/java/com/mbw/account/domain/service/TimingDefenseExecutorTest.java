package com.mbw.account.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TimingDefenseExecutorTest {

    @Test
    void should_return_body_value() {
        String result = TimingDefenseExecutor.executeInConstantTime(Duration.ofMillis(50), () -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void should_pad_fast_body_to_target_duration() {
        Duration target = Duration.ofMillis(150);
        long startNanos = System.nanoTime();

        TimingDefenseExecutor.executeInConstantTime(target, () -> "fast");

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        // Allow ±50ms tolerance for OS scheduler jitter on CI
        assertThat(elapsedMs).isGreaterThanOrEqualTo(target.toMillis() - 5);
        assertThat(elapsedMs).isLessThan(target.toMillis() + 100);
    }

    @Test
    void should_not_extend_beyond_body_when_body_runs_longer_than_target() {
        Duration target = Duration.ofMillis(20);
        long startNanos = System.nanoTime();

        TimingDefenseExecutor.executeInConstantTime(target, () -> {
            sleepUninterruptibly(100);
            return "slow";
        });

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        // Body alone takes ~100ms; total should be near that, not pad to a multiple
        assertThat(elapsedMs).isGreaterThanOrEqualTo(100);
        assertThat(elapsedMs).isLessThan(180);
    }

    @Test
    void should_propagate_body_exception_after_padding() {
        Duration target = Duration.ofMillis(100);
        long startNanos = System.nanoTime();

        assertThatThrownBy(() -> TimingDefenseExecutor.executeInConstantTime(target, () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        // Even though body threw immediately, the pad still ran
        assertThat(elapsedMs)
                .as("pad should run on exception path so attackers can't time-distinguish failure")
                .isGreaterThanOrEqualTo(target.toMillis() - 5);
    }

    @Test
    void should_invoke_body_exactly_once() {
        AtomicInteger calls = new AtomicInteger();

        TimingDefenseExecutor.executeInConstantTime(Duration.ofMillis(20), () -> {
            calls.incrementAndGet();
            return null;
        });

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void should_restore_interrupt_flag_when_pad_sleep_is_interrupted() throws InterruptedException {
        Duration target = Duration.ofSeconds(5);
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicBoolean interruptSeen = new AtomicBoolean(false);

        Thread worker = new Thread(() -> {
            TimingDefenseExecutor.executeInConstantTime(target, () -> "fast");
            // After sleep is interrupted, executor should set the interrupt flag
            interruptSeen.set(Thread.currentThread().isInterrupted());
            done.set(true);
        });
        worker.start();

        // Let the body run + start the pad sleep, then interrupt
        Thread.sleep(100);
        worker.interrupt();
        worker.join(2_000);

        assertThat(done.get())
                .as("worker thread should finish promptly after interrupt")
                .isTrue();
        assertThat(interruptSeen.get())
                .as("interrupt flag preserved per Thread.currentThread().interrupt() in catch block")
                .isTrue();
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
