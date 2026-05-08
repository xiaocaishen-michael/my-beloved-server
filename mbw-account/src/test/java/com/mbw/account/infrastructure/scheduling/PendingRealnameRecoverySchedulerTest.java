package com.mbw.account.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.application.port.QueryVerificationResult;
import com.mbw.account.application.port.QueryVerificationResult.Outcome;
import com.mbw.account.application.port.RealnameVerificationProvider;
import com.mbw.account.domain.exception.ProviderErrorException;
import com.mbw.account.domain.exception.ProviderTimeoutException;
import com.mbw.account.domain.model.FailedReason;
import com.mbw.account.domain.model.RealnameProfile;
import com.mbw.account.domain.model.RealnameStatus;
import com.mbw.account.domain.repository.RealnameProfileRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PendingRealnameRecoverySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-05-21T08:00:00Z");
    private static final Instant THRESHOLD = NOW.minus(PendingRealnameRecoveryScheduler.STALE_THRESHOLD);

    @Mock
    private RealnameProfileRepository realnameProfileRepository;

    @Mock
    private RealnameVerificationProvider provider;

    private MeterRegistry meterRegistry;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private PendingRealnameRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new PendingRealnameRecoveryScheduler(realnameProfileRepository, provider, meterRegistry, clock);
    }

    @Test
    void should_skip_provider_call_when_no_stale_rows() {
        when(realnameProfileRepository.findStalePendingOlderThan(THRESHOLD, PendingRealnameRecoveryScheduler.LIMIT))
                .thenReturn(List.of());

        scheduler.recoverStalePendingRows();

        verify(provider, never()).queryVerification(org.mockito.ArgumentMatchers.anyString());
        verify(realnameProfileRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void should_mark_VERIFIED_when_provider_returns_PASSED() {
        RealnameProfile pending = stalePending(101L, "biz-pass");
        when(realnameProfileRepository.findStalePendingOlderThan(THRESHOLD, PendingRealnameRecoveryScheduler.LIMIT))
                .thenReturn(List.of(pending));
        when(provider.queryVerification("biz-pass")).thenReturn(new QueryVerificationResult(Outcome.PASSED, null));

        scheduler.recoverStalePendingRows();

        ArgumentCaptor<RealnameProfile> saved = ArgumentCaptor.forClass(RealnameProfile.class);
        verify(realnameProfileRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(RealnameStatus.VERIFIED);
        assertThat(saved.getValue().verifiedAt()).isEqualTo(NOW);
        assertThat(counter("account.realname.recovery.scanned")).isEqualTo(1L);
        assertThat(counter("account.realname.recovery.recovered")).isEqualTo(1L);
    }

    @Test
    void should_mark_FAILED_NAME_ID_MISMATCH_when_provider_returns_NAME_ID_NOT_MATCH() {
        RealnameProfile pending = stalePending(102L, "biz-mismatch");
        when(realnameProfileRepository.findStalePendingOlderThan(THRESHOLD, PendingRealnameRecoveryScheduler.LIMIT))
                .thenReturn(List.of(pending));
        when(provider.queryVerification("biz-mismatch"))
                .thenReturn(new QueryVerificationResult(Outcome.NAME_ID_NOT_MATCH, "name/id mismatch"));

        scheduler.recoverStalePendingRows();

        ArgumentCaptor<RealnameProfile> saved = ArgumentCaptor.forClass(RealnameProfile.class);
        verify(realnameProfileRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(RealnameStatus.FAILED);
        assertThat(saved.getValue().failedReason()).isEqualTo(FailedReason.NAME_ID_MISMATCH);
    }

    @Test
    void should_mark_FAILED_LIVENESS_FAILED_when_provider_returns_LIVENESS_FAILED() {
        RealnameProfile pending = stalePending(103L, "biz-liveness");
        when(realnameProfileRepository.findStalePendingOlderThan(THRESHOLD, PendingRealnameRecoveryScheduler.LIMIT))
                .thenReturn(List.of(pending));
        when(provider.queryVerification("biz-liveness"))
                .thenReturn(new QueryVerificationResult(Outcome.LIVENESS_FAILED, null));

        scheduler.recoverStalePendingRows();

        ArgumentCaptor<RealnameProfile> saved = ArgumentCaptor.forClass(RealnameProfile.class);
        verify(realnameProfileRepository).save(saved.capture());
        assertThat(saved.getValue().failedReason()).isEqualTo(FailedReason.LIVENESS_FAILED);
    }

    @Test
    void should_mark_FAILED_USER_CANCELED_when_provider_returns_USER_CANCELED() {
        RealnameProfile pending = stalePending(104L, "biz-cancel");
        when(realnameProfileRepository.findStalePendingOlderThan(THRESHOLD, PendingRealnameRecoveryScheduler.LIMIT))
                .thenReturn(List.of(pending));
        when(provider.queryVerification("biz-cancel"))
                .thenReturn(new QueryVerificationResult(Outcome.USER_CANCELED, null));

        scheduler.recoverStalePendingRows();

        ArgumentCaptor<RealnameProfile> saved = ArgumentCaptor.forClass(RealnameProfile.class);
        verify(realnameProfileRepository).save(saved.capture());
        assertThat(saved.getValue().failedReason()).isEqualTo(FailedReason.USER_CANCELED);
    }

    @Test
    void should_skip_save_when_provider_times_out() {
        RealnameProfile pending = stalePending(105L, "biz-timeout");
        when(realnameProfileRepository.findStalePendingOlderThan(THRESHOLD, PendingRealnameRecoveryScheduler.LIMIT))
                .thenReturn(List.of(pending));
        when(provider.queryVerification("biz-timeout"))
                .thenThrow(new ProviderTimeoutException(new RuntimeException("aliyun slow")));

        scheduler.recoverStalePendingRows();

        verify(realnameProfileRepository, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(counter("account.realname.recovery.timeouts")).isEqualTo(1L);
    }

    @Test
    void should_mark_FAILED_PROVIDER_ERROR_when_provider_returns_biz_error() {
        RealnameProfile pending = stalePending(106L, "biz-error");
        when(realnameProfileRepository.findStalePendingOlderThan(THRESHOLD, PendingRealnameRecoveryScheduler.LIMIT))
                .thenReturn(List.of(pending));
        when(provider.queryVerification("biz-error")).thenThrow(new ProviderErrorException("unknown bizId"));

        scheduler.recoverStalePendingRows();

        ArgumentCaptor<RealnameProfile> saved = ArgumentCaptor.forClass(RealnameProfile.class);
        verify(realnameProfileRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(RealnameStatus.FAILED);
        assertThat(saved.getValue().failedReason()).isEqualTo(FailedReason.PROVIDER_ERROR);
        assertThat(counter("account.realname.recovery.errors")).isEqualTo(1L);
    }

    @Test
    void should_continue_batch_when_one_row_times_out_and_another_succeeds() {
        RealnameProfile a = stalePending(201L, "biz-a-timeout");
        RealnameProfile b = stalePending(202L, "biz-b-pass");
        when(realnameProfileRepository.findStalePendingOlderThan(THRESHOLD, PendingRealnameRecoveryScheduler.LIMIT))
                .thenReturn(List.of(a, b));
        when(provider.queryVerification("biz-a-timeout"))
                .thenThrow(new ProviderTimeoutException(new RuntimeException("slow")));
        when(provider.queryVerification("biz-b-pass")).thenReturn(new QueryVerificationResult(Outcome.PASSED, null));

        scheduler.recoverStalePendingRows();

        verify(provider).queryVerification(eq("biz-a-timeout"));
        verify(provider).queryVerification(eq("biz-b-pass"));
        verify(realnameProfileRepository, times(1)).save(org.mockito.ArgumentMatchers.any());
    }

    private static RealnameProfile stalePending(long accountId, String bizId) {
        Instant past = NOW.minusSeconds(900); // 15 min ago, well past threshold
        return RealnameProfile.unverified(accountId, past)
                .withPending(new byte[] {1}, new byte[] {2}, "h".repeat(64), bizId, past);
    }

    private long counter(String name) {
        var c = meterRegistry.find(name).counter();
        return c == null ? 0L : (long) c.count();
    }
}
