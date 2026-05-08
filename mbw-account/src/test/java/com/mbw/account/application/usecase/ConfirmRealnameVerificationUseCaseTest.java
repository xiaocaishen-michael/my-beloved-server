package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.application.command.ConfirmRealnameCommand;
import com.mbw.account.application.port.QueryVerificationResult;
import com.mbw.account.application.port.QueryVerificationResult.Outcome;
import com.mbw.account.application.port.RealnameVerificationProvider;
import com.mbw.account.application.result.ConfirmRealnameResult;
import com.mbw.account.domain.exception.ProviderTimeoutException;
import com.mbw.account.domain.exception.RealnameProfileAccessDeniedException;
import com.mbw.account.domain.exception.RealnameProfileNotFoundException;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.FailedReason;
import com.mbw.account.domain.model.RealnameProfile;
import com.mbw.account.domain.model.RealnameStatus;
import com.mbw.account.domain.repository.RealnameProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
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
class ConfirmRealnameVerificationUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-05-21T09:00:00Z");
    private static final AccountId CALLER = new AccountId(7001L);
    private static final long OTHER_ACCOUNT = 7002L;
    private static final String BIZ_ID = "biz-confirm-1";
    private static final byte[] REAL_NAME_ENC = {1, 2, 3};
    private static final byte[] ID_CARD_ENC = {4, 5, 6};
    private static final String ID_CARD_HASH = "h".repeat(64);

    @Mock
    private RealnameProfileRepository realnameProfileRepository;

    @Mock
    private RealnameVerificationProvider provider;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private ConfirmRealnameVerificationUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ConfirmRealnameVerificationUseCase(realnameProfileRepository, provider, clock);
    }

    @Test
    void should_throw_NotFound_when_providerBizId_unknown() {
        when(realnameProfileRepository.findByProviderBizId(BIZ_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new ConfirmRealnameCommand(CALLER, BIZ_ID)))
                .isInstanceOf(RealnameProfileNotFoundException.class);
        verify(provider, never()).queryVerification(any());
        verify(realnameProfileRepository, never()).save(any());
    }

    @Test
    void should_throw_AccessDenied_when_caller_not_owner() {
        RealnameProfile pending = profile(OTHER_ACCOUNT, RealnameStatus.PENDING, null, null);
        when(realnameProfileRepository.findByProviderBizId(BIZ_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> useCase.execute(new ConfirmRealnameCommand(CALLER, BIZ_ID)))
                .isInstanceOf(RealnameProfileAccessDeniedException.class);
        verify(provider, never()).queryVerification(any());
        verify(realnameProfileRepository, never()).save(any());
    }

    @Test
    void should_return_existing_VERIFIED_result_idempotently_when_already_verified() {
        Instant verifiedAt = NOW.minusSeconds(120);
        RealnameProfile verified = profile(CALLER.value(), RealnameStatus.VERIFIED, verifiedAt, null);
        when(realnameProfileRepository.findByProviderBizId(BIZ_ID)).thenReturn(Optional.of(verified));

        ConfirmRealnameResult result = useCase.execute(new ConfirmRealnameCommand(CALLER, BIZ_ID));

        assertThat(result.status()).isEqualTo(RealnameStatus.VERIFIED);
        assertThat(result.verifiedAt()).isEqualTo(verifiedAt);
        assertThat(result.failedReason()).isNull();
        assertThat(result.providerBizId()).isEqualTo(BIZ_ID);
        verify(provider, never()).queryVerification(any());
        verify(realnameProfileRepository, never()).save(any());
    }

    @Test
    void should_return_existing_FAILED_result_idempotently_when_already_failed() {
        RealnameProfile failed = profile(CALLER.value(), RealnameStatus.FAILED, null, FailedReason.NAME_ID_MISMATCH);
        when(realnameProfileRepository.findByProviderBizId(BIZ_ID)).thenReturn(Optional.of(failed));

        ConfirmRealnameResult result = useCase.execute(new ConfirmRealnameCommand(CALLER, BIZ_ID));

        assertThat(result.status()).isEqualTo(RealnameStatus.FAILED);
        assertThat(result.failedReason()).isEqualTo(FailedReason.NAME_ID_MISMATCH);
        assertThat(result.verifiedAt()).isNull();
        verify(provider, never()).queryVerification(any());
        verify(realnameProfileRepository, never()).save(any());
    }

    @Test
    void should_persist_VERIFIED_and_return_VERIFIED_when_provider_returns_PASSED() {
        RealnameProfile pending = profile(CALLER.value(), RealnameStatus.PENDING, null, null);
        when(realnameProfileRepository.findByProviderBizId(BIZ_ID)).thenReturn(Optional.of(pending));
        when(provider.queryVerification(BIZ_ID)).thenReturn(new QueryVerificationResult(Outcome.PASSED, null));
        when(realnameProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConfirmRealnameResult result = useCase.execute(new ConfirmRealnameCommand(CALLER, BIZ_ID));

        assertThat(result.status()).isEqualTo(RealnameStatus.VERIFIED);
        assertThat(result.verifiedAt()).isEqualTo(NOW);
        ArgumentCaptor<RealnameProfile> saved = ArgumentCaptor.forClass(RealnameProfile.class);
        verify(realnameProfileRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(RealnameStatus.VERIFIED);
    }

    @Test
    void should_persist_FAILED_NAME_ID_MISMATCH_when_provider_returns_NAME_ID_NOT_MATCH() {
        assertOutcomeMapping(Outcome.NAME_ID_NOT_MATCH, FailedReason.NAME_ID_MISMATCH);
    }

    @Test
    void should_persist_FAILED_LIVENESS_FAILED_when_provider_returns_LIVENESS_FAILED() {
        assertOutcomeMapping(Outcome.LIVENESS_FAILED, FailedReason.LIVENESS_FAILED);
    }

    @Test
    void should_persist_FAILED_USER_CANCELED_when_provider_returns_USER_CANCELED() {
        assertOutcomeMapping(Outcome.USER_CANCELED, FailedReason.USER_CANCELED);
    }

    @Test
    void should_propagate_ProviderTimeout_and_keep_PENDING_when_provider_times_out() {
        RealnameProfile pending = profile(CALLER.value(), RealnameStatus.PENDING, null, null);
        when(realnameProfileRepository.findByProviderBizId(BIZ_ID)).thenReturn(Optional.of(pending));
        when(provider.queryVerification(BIZ_ID))
                .thenThrow(new ProviderTimeoutException(new RuntimeException("aliyun slow")));

        assertThatThrownBy(() -> useCase.execute(new ConfirmRealnameCommand(CALLER, BIZ_ID)))
                .isInstanceOf(ProviderTimeoutException.class);
        verify(realnameProfileRepository, never()).save(any());
    }

    private void assertOutcomeMapping(Outcome outcome, FailedReason expected) {
        RealnameProfile pending = profile(CALLER.value(), RealnameStatus.PENDING, null, null);
        when(realnameProfileRepository.findByProviderBizId(BIZ_ID)).thenReturn(Optional.of(pending));
        when(provider.queryVerification(BIZ_ID)).thenReturn(new QueryVerificationResult(outcome, null));
        when(realnameProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConfirmRealnameResult result = useCase.execute(new ConfirmRealnameCommand(CALLER, BIZ_ID));

        assertThat(result.status()).isEqualTo(RealnameStatus.FAILED);
        assertThat(result.failedReason()).isEqualTo(expected);
        ArgumentCaptor<RealnameProfile> saved = ArgumentCaptor.forClass(RealnameProfile.class);
        verify(realnameProfileRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(RealnameStatus.FAILED);
        assertThat(saved.getValue().failedReason()).isEqualTo(expected);
    }

    private static RealnameProfile profile(
            long accountId, RealnameStatus status, Instant verifiedAt, FailedReason failedReason) {
        return RealnameProfile.reconstitute(
                100L,
                accountId,
                status,
                REAL_NAME_ENC,
                ID_CARD_ENC,
                ID_CARD_HASH,
                BIZ_ID,
                verifiedAt,
                failedReason,
                failedReason == null ? null : NOW.minusSeconds(60),
                0,
                NOW.minusSeconds(900),
                NOW.minusSeconds(60));
    }
}
