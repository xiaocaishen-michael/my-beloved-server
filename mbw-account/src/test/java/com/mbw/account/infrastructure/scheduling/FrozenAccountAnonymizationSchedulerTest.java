package com.mbw.account.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.application.usecase.AnonymizeFrozenAccountUseCase;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.repository.AccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FrozenAccountAnonymizationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-05-21T03:00:00Z");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AnonymizeFrozenAccountUseCase useCase;

    private MeterRegistry meterRegistry;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private FrozenAccountAnonymizationScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new FrozenAccountAnonymizationScheduler(accountRepository, useCase, meterRegistry, clock);
    }

    @Test
    void should_anonymize_all_eligible_ids_when_use_case_succeeds() {
        AccountId a = new AccountId(1L);
        AccountId b = new AccountId(2L);
        AccountId c = new AccountId(3L);
        when(accountRepository.findFrozenWithExpiredGracePeriod(NOW, FrozenAccountAnonymizationScheduler.LIMIT))
                .thenReturn(List.of(a, b, c));

        scheduler.anonymizeExpiredFrozenAccounts();

        verify(useCase, org.mockito.Mockito.times(3)).execute(any());
        assertThat(counter("account.anonymize.scanned")).isEqualTo(3L);
        assertThat(counter("account.anonymize.succeeded")).isEqualTo(3L);
        assertThat(counter("account.anonymize.failures")).isZero();
    }

    @Test
    void should_continue_batch_when_one_row_throws_RuntimeException() {
        AccountId a = new AccountId(1L);
        AccountId b = new AccountId(2L);
        AccountId c = new AccountId(3L);
        when(accountRepository.findFrozenWithExpiredGracePeriod(NOW, FrozenAccountAnonymizationScheduler.LIMIT))
                .thenReturn(List.of(a, b, c));
        doThrow(new RuntimeException("middle row boom"))
                .when(useCase)
                .execute(org.mockito.ArgumentMatchers.argThat(
                        cmd -> cmd != null && cmd.accountId().equals(b)));

        scheduler.anonymizeExpiredFrozenAccounts();

        assertThat(counter("account.anonymize.scanned")).isEqualTo(3L);
        assertThat(counter("account.anonymize.succeeded")).isEqualTo(2L);
        assertThat(counterTagged("account.anonymize.failures", "reason", "RuntimeException"))
                .isEqualTo(1L);
        assertThat(counter("account.anonymize.persistent_failures")).isZero();
    }

    @Test
    void should_silently_skip_IllegalStateException_per_FR_005() {
        AccountId a = new AccountId(1L);
        when(accountRepository.findFrozenWithExpiredGracePeriod(NOW, FrozenAccountAnonymizationScheduler.LIMIT))
                .thenReturn(List.of(a));
        doThrow(new IllegalStateException("status drift")).when(useCase).execute(any());

        scheduler.anonymizeExpiredFrozenAccounts();

        assertThat(counter("account.anonymize.scanned")).isEqualTo(1L);
        assertThat(counter("account.anonymize.succeeded")).isZero();
        assertThat(counter("account.anonymize.failures")).isZero();
    }

    @Test
    void should_silently_skip_OptimisticLockingFailureException() {
        AccountId a = new AccountId(1L);
        when(accountRepository.findFrozenWithExpiredGracePeriod(NOW, FrozenAccountAnonymizationScheduler.LIMIT))
                .thenReturn(List.of(a));
        doThrow(new OptimisticLockingFailureException("lock lost"))
                .when(useCase)
                .execute(any());

        scheduler.anonymizeExpiredFrozenAccounts();

        assertThat(counter("account.anonymize.scanned")).isEqualTo(1L);
        assertThat(counter("account.anonymize.failures")).isZero();
    }

    @Test
    void should_increment_persistent_failures_after_threshold_consecutive_failures_for_same_id() {
        AccountId a = new AccountId(1L);
        when(accountRepository.findFrozenWithExpiredGracePeriod(NOW, FrozenAccountAnonymizationScheduler.LIMIT))
                .thenReturn(List.of(a));
        doThrow(new RuntimeException("persistent")).when(useCase).execute(any());

        scheduler.anonymizeExpiredFrozenAccounts(); // count=1
        scheduler.anonymizeExpiredFrozenAccounts(); // count=2
        scheduler.anonymizeExpiredFrozenAccounts(); // count=3 → ERROR + persistent_failures++

        // Failures counter is recorded with the exception class as a "reason"
        // tag, so the read uses the tagged variant; an untagged read is a
        // distinct meter and would always be 0.
        assertThat(counterTagged("account.anonymize.failures", "reason", "RuntimeException"))
                .isEqualTo(3L);
        assertThat(counter("account.anonymize.persistent_failures")).isEqualTo(1L);
    }

    @Test
    void should_clear_failure_count_on_subsequent_success() {
        AccountId a = new AccountId(1L);
        when(accountRepository.findFrozenWithExpiredGracePeriod(NOW, FrozenAccountAnonymizationScheduler.LIMIT))
                .thenReturn(List.of(a));
        doThrow(new RuntimeException("first attempt")).when(useCase).execute(any());

        scheduler.anonymizeExpiredFrozenAccounts(); // failure → count=1

        org.mockito.Mockito.reset(useCase); // next tick: success
        scheduler.anonymizeExpiredFrozenAccounts(); // count cleared

        // Now fail twice — should NOT cross threshold immediately because counter reset
        doThrow(new RuntimeException("second")).when(useCase).execute(any());
        scheduler.anonymizeExpiredFrozenAccounts(); // count=1
        scheduler.anonymizeExpiredFrozenAccounts(); // count=2 (still under threshold)

        assertThat(counter("account.anonymize.persistent_failures")).isZero();
    }

    @Test
    void should_handle_empty_batch_without_invoking_use_case() {
        when(accountRepository.findFrozenWithExpiredGracePeriod(NOW, FrozenAccountAnonymizationScheduler.LIMIT))
                .thenReturn(List.of());

        scheduler.anonymizeExpiredFrozenAccounts();

        verify(useCase, org.mockito.Mockito.never()).execute(any());
        assertThat(counter("account.anonymize.scanned")).isZero();
        assertThat(counter("account.anonymize.succeeded")).isZero();
        // Timer still records the empty pass
        assertThat(meterRegistry.timer("account.anonymize.batch_duration").count())
                .isEqualTo(1L);
    }

    @Test
    void method_should_carry_Scheduled_annotation_with_03_AM_Asia_Shanghai_cron() throws NoSuchMethodException {
        Method m = FrozenAccountAnonymizationScheduler.class.getMethod("anonymizeExpiredFrozenAccounts");
        Scheduled annotation = m.getAnnotation(Scheduled.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.cron()).isEqualTo("0 0 3 * * *");
        assertThat(annotation.zone()).isEqualTo("Asia/Shanghai");
    }

    private long counter(String name) {
        return (long) meterRegistry.counter(name).count();
    }

    private long counterTagged(String name, String tagKey, String tagValue) {
        return (long) meterRegistry.counter(name, tagKey, tagValue).count();
    }
}
