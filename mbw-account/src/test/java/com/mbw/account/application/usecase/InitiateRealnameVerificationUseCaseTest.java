package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.application.command.InitiateRealnameCommand;
import com.mbw.account.application.port.CipherService;
import com.mbw.account.application.port.InitVerificationRequest;
import com.mbw.account.application.port.InitVerificationResult;
import com.mbw.account.application.port.RealnameVerificationProvider;
import com.mbw.account.application.result.InitiateRealnameResult;
import com.mbw.account.application.service.IdentityHashService;
import com.mbw.account.domain.exception.AccountInFreezePeriodException;
import com.mbw.account.domain.exception.AgreementRequiredException;
import com.mbw.account.domain.exception.AlreadyVerifiedException;
import com.mbw.account.domain.exception.IdCardOccupiedException;
import com.mbw.account.domain.exception.InvalidIdCardFormatException;
import com.mbw.account.domain.exception.ProviderErrorException;
import com.mbw.account.domain.exception.ProviderTimeoutException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.FailedReason;
import com.mbw.account.domain.model.RealnameProfile;
import com.mbw.account.domain.model.RealnameStatus;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.RealnameProfileRepository;
import com.mbw.shared.web.RateLimitService;
import com.mbw.shared.web.RateLimitedException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class InitiateRealnameVerificationUseCaseTest {

    private static final AccountId ACCOUNT_ID = new AccountId(1001L);
    private static final long OTHER_ACCOUNT_ID = 2002L;
    private static final String REAL_NAME = "张三";
    private static final String VALID_ID_CARD = "11010119900101004X";
    private static final String AGREEMENT_VERSION = "v1";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String ID_CARD_HASH = "deadbeef".repeat(8);
    private static final byte[] REAL_NAME_ENC = {1, 2, 3};
    private static final byte[] ID_CARD_ENC = {4, 5, 6};
    private static final String LIVENESS_URL = "https://aliyun-bypass.local/face?token=abc";

    private TransactionTemplate transactionTemplate;
    private AccountRepository accountRepository;
    private RealnameProfileRepository realnameProfileRepository;
    private IdentityHashService identityHashService;
    private RateLimitService rateLimitService;
    private CipherService cipherService;
    private RealnameVerificationProvider provider;
    private InitiateRealnameVerificationUseCase useCase;

    @BeforeEach
    void setUp() {
        transactionTemplate = Mockito.mock(TransactionTemplate.class);
        accountRepository = Mockito.mock(AccountRepository.class);
        realnameProfileRepository = Mockito.mock(RealnameProfileRepository.class);
        identityHashService = Mockito.mock(IdentityHashService.class);
        rateLimitService = Mockito.mock(RateLimitService.class);
        cipherService = Mockito.mock(CipherService.class);
        provider = Mockito.mock(RealnameVerificationProvider.class);
        useCase = new InitiateRealnameVerificationUseCase(
                transactionTemplate,
                accountRepository,
                realnameProfileRepository,
                identityHashService,
                rateLimitService,
                cipherService,
                provider);

        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        Mockito.doAnswer(inv -> {
                    java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action =
                            inv.getArgument(0);
                    action.accept(null);
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
    }

    @Test
    void should_throw_AccountInFreezePeriod_when_account_is_FROZEN() {
        stubAccountWithStatus(AccountStatus.FROZEN);

        assertThatThrownBy(() -> useCase.execute(happyCommand())).isInstanceOf(AccountInFreezePeriodException.class);

        verify(provider, never()).initVerification(any());
        verify(realnameProfileRepository, never()).save(any());
    }

    @Test
    void should_throw_AgreementRequired_when_agreementVersion_is_blank() {
        stubAccountWithStatus(AccountStatus.ACTIVE);

        InitiateRealnameCommand cmd =
                new InitiateRealnameCommand(ACCOUNT_ID, REAL_NAME, VALID_ID_CARD, "  ", CLIENT_IP);

        assertThatThrownBy(() -> useCase.execute(cmd)).isInstanceOf(AgreementRequiredException.class);
        verify(provider, never()).initVerification(any());
        verify(realnameProfileRepository, never()).save(any());
    }

    @Test
    void should_throw_InvalidIdCardFormat_when_validator_rejects_idCardNo() {
        stubAccountWithStatus(AccountStatus.ACTIVE);

        InitiateRealnameCommand cmd =
                new InitiateRealnameCommand(ACCOUNT_ID, REAL_NAME, "not-an-id-card", AGREEMENT_VERSION, CLIENT_IP);

        assertThatThrownBy(() -> useCase.execute(cmd)).isInstanceOf(InvalidIdCardFormatException.class);
        verify(provider, never()).initVerification(any());
    }

    @Test
    void should_propagate_RateLimited_when_account_bucket_exceeded() {
        stubAccountWithStatus(AccountStatus.ACTIVE);
        Mockito.doThrow(new RateLimitedException("realname:account:1001", Duration.ofSeconds(60)))
                .when(rateLimitService)
                .consumeOrThrow(Mockito.startsWith("realname:account:"), any());

        assertThatThrownBy(() -> useCase.execute(happyCommand())).isInstanceOf(RateLimitedException.class);
        verify(provider, never()).initVerification(any());
    }

    @Test
    void should_propagate_RateLimited_when_ip_bucket_exceeded() {
        stubAccountWithStatus(AccountStatus.ACTIVE);
        Mockito.doNothing().when(rateLimitService).consumeOrThrow(Mockito.startsWith("realname:account:"), any());
        Mockito.doThrow(new RateLimitedException("realname:ip:" + CLIENT_IP, Duration.ofSeconds(60)))
                .when(rateLimitService)
                .consumeOrThrow(Mockito.startsWith("realname:ip:"), any());

        assertThatThrownBy(() -> useCase.execute(happyCommand())).isInstanceOf(RateLimitedException.class);
        verify(provider, never()).initVerification(any());
    }

    @Test
    void should_throw_AlreadyVerified_when_existing_profile_is_VERIFIED() {
        stubAccountWithStatus(AccountStatus.ACTIVE);
        RealnameProfile existing = profileBuilder()
                .accountId(ACCOUNT_ID.value())
                .status(RealnameStatus.VERIFIED)
                .verifiedAt(Instant.parse("2026-04-01T10:00:00Z"))
                .build();
        when(realnameProfileRepository.findByAccountId(ACCOUNT_ID.value())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> useCase.execute(happyCommand())).isInstanceOf(AlreadyVerifiedException.class);
        verify(provider, never()).initVerification(any());
        verify(realnameProfileRepository, never()).save(any());
    }

    @Test
    void should_throw_IdCardOccupied_when_other_account_holds_active_profile_with_same_hash() {
        stubAccountWithStatus(AccountStatus.ACTIVE);
        when(realnameProfileRepository.findByAccountId(ACCOUNT_ID.value())).thenReturn(Optional.empty());
        when(identityHashService.sha256Hex(VALID_ID_CARD)).thenReturn(ID_CARD_HASH);
        RealnameProfile otherActive = profileBuilder()
                .accountId(OTHER_ACCOUNT_ID)
                .status(RealnameStatus.PENDING)
                .build();
        when(realnameProfileRepository.findByIdCardHash(ID_CARD_HASH)).thenReturn(Optional.of(otherActive));

        assertThatThrownBy(() -> useCase.execute(happyCommand())).isInstanceOf(IdCardOccupiedException.class);
        verify(provider, never()).initVerification(any());
        verify(realnameProfileRepository, never()).save(any());
    }

    @Test
    void should_compensate_to_FAILED_PROVIDER_ERROR_and_rethrow_when_provider_times_out() {
        primeHappyPathStubsUntilProviderCall();
        when(provider.initVerification(any()))
                .thenThrow(new ProviderTimeoutException(new RuntimeException("aliyun timeout")));

        ArgumentCaptor<RealnameProfile> firstSave = ArgumentCaptor.forClass(RealnameProfile.class);
        when(realnameProfileRepository.save(firstSave.capture())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> useCase.execute(happyCommand())).isInstanceOf(ProviderTimeoutException.class);

        // primeHappyPathStubs 已 stub findByProviderBizId 返回 PENDING profile。
        verify(realnameProfileRepository, times(2)).save(any());
        ArgumentCaptor<RealnameProfile> compensated = ArgumentCaptor.forClass(RealnameProfile.class);
        verify(realnameProfileRepository, times(2)).save(compensated.capture());
        RealnameProfile compensationProfile = compensated.getAllValues().get(1);
        assertThat(compensationProfile.status()).isEqualTo(RealnameStatus.FAILED);
        assertThat(compensationProfile.failedReason()).isEqualTo(FailedReason.PROVIDER_ERROR);
    }

    @Test
    void should_compensate_to_FAILED_PROVIDER_ERROR_and_rethrow_when_provider_returns_biz_error() {
        primeHappyPathStubsUntilProviderCall();
        when(provider.initVerification(any())).thenThrow(new ProviderErrorException("aliyun biz err"));

        when(realnameProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> useCase.execute(happyCommand())).isInstanceOf(ProviderErrorException.class);

        ArgumentCaptor<RealnameProfile> saves = ArgumentCaptor.forClass(RealnameProfile.class);
        verify(realnameProfileRepository, times(2)).save(saves.capture());
        RealnameProfile compensated = saves.getAllValues().get(1);
        assertThat(compensated.status()).isEqualTo(RealnameStatus.FAILED);
        assertThat(compensated.failedReason()).isEqualTo(FailedReason.PROVIDER_ERROR);
    }

    @Test
    void should_throw_IdCardOccupied_when_save_throws_DataIntegrityViolation() {
        stubAccountWithStatus(AccountStatus.ACTIVE);
        when(realnameProfileRepository.findByAccountId(ACCOUNT_ID.value())).thenReturn(Optional.empty());
        when(identityHashService.sha256Hex(VALID_ID_CARD)).thenReturn(ID_CARD_HASH);
        when(realnameProfileRepository.findByIdCardHash(ID_CARD_HASH)).thenReturn(Optional.empty());
        when(cipherService.encrypt(REAL_NAME.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .thenReturn(REAL_NAME_ENC);
        when(cipherService.encrypt(VALID_ID_CARD.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .thenReturn(ID_CARD_ENC);
        when(realnameProfileRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("uk_realname_profile_id_card_hash"));

        assertThatThrownBy(() -> useCase.execute(happyCommand())).isInstanceOf(IdCardOccupiedException.class);
        verify(provider, never()).initVerification(any());
    }

    @Test
    void should_persist_PENDING_profile_and_return_livenessUrl_on_happy_path() {
        primeHappyPathStubsUntilProviderCall();
        when(provider.initVerification(any())).thenReturn(new InitVerificationResult(LIVENESS_URL));
        when(realnameProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitiateRealnameResult result = useCase.execute(happyCommand());

        assertThat(result.livenessUrl()).isEqualTo(LIVENESS_URL);
        assertThat(result.providerBizId()).isNotBlank();

        ArgumentCaptor<RealnameProfile> saved = ArgumentCaptor.forClass(RealnameProfile.class);
        verify(realnameProfileRepository).save(saved.capture());
        RealnameProfile pending = saved.getValue();
        assertThat(pending.status()).isEqualTo(RealnameStatus.PENDING);
        assertThat(pending.idCardHash()).isEqualTo(ID_CARD_HASH);
        assertThat(pending.realNameEnc()).isEqualTo(REAL_NAME_ENC);
        assertThat(pending.idCardNoEnc()).isEqualTo(ID_CARD_ENC);
        assertThat(pending.providerBizId()).isEqualTo(result.providerBizId());

        ArgumentCaptor<InitVerificationRequest> req = ArgumentCaptor.forClass(InitVerificationRequest.class);
        verify(provider).initVerification(req.capture());
        assertThat(req.getValue().providerBizId()).isEqualTo(result.providerBizId());
        assertThat(req.getValue().realName()).isEqualTo(REAL_NAME);
        assertThat(req.getValue().idCardNo()).isEqualTo(VALID_ID_CARD);
        verify(rateLimitService).consumeOrThrow(Mockito.startsWith("realname:account:"), any());
        verify(rateLimitService).consumeOrThrow(Mockito.startsWith("realname:ip:"), any());
    }

    private void primeHappyPathStubsUntilProviderCall() {
        stubAccountWithStatus(AccountStatus.ACTIVE);
        when(realnameProfileRepository.findByAccountId(ACCOUNT_ID.value())).thenReturn(Optional.empty());
        when(identityHashService.sha256Hex(VALID_ID_CARD)).thenReturn(ID_CARD_HASH);
        when(realnameProfileRepository.findByIdCardHash(ID_CARD_HASH)).thenReturn(Optional.empty());
        when(cipherService.encrypt(REAL_NAME.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .thenReturn(REAL_NAME_ENC);
        when(cipherService.encrypt(VALID_ID_CARD.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .thenReturn(ID_CARD_ENC);
        // compensation 路径需要：通过 providerBizId 找回刚 save 的 PENDING profile。
        when(realnameProfileRepository.findByProviderBizId(anyString()))
                .thenAnswer(inv -> Optional.of(profileBuilder()
                        .accountId(ACCOUNT_ID.value())
                        .status(RealnameStatus.PENDING)
                        .providerBizId(inv.getArgument(0))
                        .build()));
    }

    private InitiateRealnameCommand happyCommand() {
        return new InitiateRealnameCommand(ACCOUNT_ID, REAL_NAME, VALID_ID_CARD, AGREEMENT_VERSION, CLIENT_IP);
    }

    private void stubAccountWithStatus(AccountStatus status) {
        Account account = Mockito.mock(Account.class);
        when(account.status()).thenReturn(status);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
    }

    private static ProfileBuilder profileBuilder() {
        return new ProfileBuilder();
    }

    private static final class ProfileBuilder {
        private Long id = 100L;
        private long accountId = ACCOUNT_ID.value();
        private RealnameStatus status = RealnameStatus.UNVERIFIED;
        private byte[] realNameEnc = REAL_NAME_ENC;
        private byte[] idCardNoEnc = ID_CARD_ENC;
        private String idCardHash = ID_CARD_HASH;
        private String providerBizId = "biz-1";
        private Instant verifiedAt;
        private FailedReason failedReason;
        private Instant failedAt;
        private int retryCount24h = 0;
        private final Instant createdAt = Instant.parse("2026-03-01T00:00:00Z");
        private final Instant updatedAt = Instant.parse("2026-04-01T10:00:00Z");

        ProfileBuilder accountId(long v) {
            this.accountId = v;
            return this;
        }

        ProfileBuilder status(RealnameStatus v) {
            this.status = v;
            return this;
        }

        ProfileBuilder verifiedAt(Instant v) {
            this.verifiedAt = v;
            return this;
        }

        ProfileBuilder providerBizId(String v) {
            this.providerBizId = v;
            return this;
        }

        RealnameProfile build() {
            return RealnameProfile.reconstitute(
                    id,
                    accountId,
                    status,
                    realNameEnc,
                    idCardNoEnc,
                    idCardHash,
                    providerBizId,
                    verifiedAt,
                    failedReason,
                    failedAt,
                    retryCount24h,
                    createdAt,
                    updatedAt);
        }
    }
}
