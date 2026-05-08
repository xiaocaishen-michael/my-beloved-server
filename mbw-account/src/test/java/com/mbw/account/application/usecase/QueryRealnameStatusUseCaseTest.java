package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.application.port.CipherService;
import com.mbw.account.application.result.RealnameStatusResult;
import com.mbw.account.domain.model.FailedReason;
import com.mbw.account.domain.model.RealnameProfile;
import com.mbw.account.domain.model.RealnameStatus;
import com.mbw.account.domain.repository.RealnameProfileRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class QueryRealnameStatusUseCaseTest {

    private static final long ACCOUNT_ID = 1001L;
    private static final byte[] REAL_NAME_ENC = {1, 2, 3};
    private static final byte[] ID_CARD_ENC = {4, 5, 6};

    private RealnameProfileRepository realnameProfileRepository;
    private CipherService cipherService;
    private QueryRealnameStatusUseCase useCase;

    @BeforeEach
    void setUp() {
        realnameProfileRepository = Mockito.mock(RealnameProfileRepository.class);
        cipherService = Mockito.mock(CipherService.class);
        useCase = new QueryRealnameStatusUseCase(realnameProfileRepository, cipherService);
    }

    @Test
    void execute_should_return_UNVERIFIED_with_null_fields_when_no_profile_row_exists() {
        when(realnameProfileRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        RealnameStatusResult result = useCase.execute(ACCOUNT_ID);

        assertThat(result.status()).isEqualTo(RealnameStatus.UNVERIFIED);
        assertThat(result.realNameMasked()).isNull();
        assertThat(result.idCardMasked()).isNull();
        assertThat(result.verifiedAt()).isNull();
        assertThat(result.failedReason()).isNull();
        verify(cipherService, never()).decrypt(any());
    }

    @Test
    void execute_should_decrypt_and_mask_when_status_is_VERIFIED() {
        Instant verifiedAt = Instant.parse("2026-04-01T10:00:00Z");
        RealnameProfile verified = profileBuilder()
                .status(RealnameStatus.VERIFIED)
                .realNameEnc(REAL_NAME_ENC)
                .idCardNoEnc(ID_CARD_ENC)
                .verifiedAt(verifiedAt)
                .build();
        when(realnameProfileRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(verified));
        when(cipherService.decrypt(REAL_NAME_ENC)).thenReturn("张三".getBytes(StandardCharsets.UTF_8));
        when(cipherService.decrypt(ID_CARD_ENC)).thenReturn("11010119900101001X".getBytes(StandardCharsets.UTF_8));

        RealnameStatusResult result = useCase.execute(ACCOUNT_ID);

        assertThat(result.status()).isEqualTo(RealnameStatus.VERIFIED);
        assertThat(result.realNameMasked()).isEqualTo("*三");
        assertThat(result.idCardMasked()).isEqualTo("1****************X");
        assertThat(result.verifiedAt()).isEqualTo(verifiedAt);
        assertThat(result.failedReason()).isNull();
        verify(cipherService).decrypt(REAL_NAME_ENC);
        verify(cipherService).decrypt(ID_CARD_ENC);
    }

    @Test
    void execute_should_return_PENDING_status_only_without_decrypt_when_status_is_PENDING() {
        RealnameProfile pending = profileBuilder()
                .status(RealnameStatus.PENDING)
                .realNameEnc(REAL_NAME_ENC)
                .idCardNoEnc(ID_CARD_ENC)
                .build();
        when(realnameProfileRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(pending));

        RealnameStatusResult result = useCase.execute(ACCOUNT_ID);

        assertThat(result.status()).isEqualTo(RealnameStatus.PENDING);
        assertThat(result.realNameMasked()).isNull();
        assertThat(result.idCardMasked()).isNull();
        assertThat(result.verifiedAt()).isNull();
        assertThat(result.failedReason()).isNull();
        verify(cipherService, never()).decrypt(any());
    }

    @Test
    void execute_should_return_FAILED_with_failedReason_without_decrypt_when_status_is_FAILED() {
        RealnameProfile failed = profileBuilder()
                .status(RealnameStatus.FAILED)
                .realNameEnc(REAL_NAME_ENC)
                .idCardNoEnc(ID_CARD_ENC)
                .failedReason(FailedReason.NAME_ID_MISMATCH)
                .failedAt(Instant.parse("2026-04-01T10:00:00Z"))
                .build();
        when(realnameProfileRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(failed));

        RealnameStatusResult result = useCase.execute(ACCOUNT_ID);

        assertThat(result.status()).isEqualTo(RealnameStatus.FAILED);
        assertThat(result.failedReason()).isEqualTo(FailedReason.NAME_ID_MISMATCH);
        assertThat(result.realNameMasked()).isNull();
        assertThat(result.idCardMasked()).isNull();
        assertThat(result.verifiedAt()).isNull();
        verify(cipherService, never()).decrypt(any());
    }

    @Test
    void execute_should_bubble_up_when_cipher_decrypt_throws() {
        RealnameProfile verified = profileBuilder()
                .status(RealnameStatus.VERIFIED)
                .realNameEnc(REAL_NAME_ENC)
                .idCardNoEnc(ID_CARD_ENC)
                .verifiedAt(Instant.parse("2026-04-01T10:00:00Z"))
                .build();
        when(realnameProfileRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(verified));
        when(cipherService.decrypt(REAL_NAME_ENC)).thenThrow(new IllegalStateException("AES-GCM decryption failed"));

        assertThatThrownBy(() -> useCase.execute(ACCOUNT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AES-GCM decryption failed");
    }

    private static ProfileBuilder profileBuilder() {
        return new ProfileBuilder();
    }

    private static final class ProfileBuilder {
        private RealnameStatus status;
        private byte[] realNameEnc;
        private byte[] idCardNoEnc;
        private String idCardHash = "abc";
        private String providerBizId = "biz-1";
        private Instant verifiedAt;
        private FailedReason failedReason;
        private Instant failedAt;
        private int retryCount24h = 0;
        private final Instant createdAt = Instant.parse("2026-03-01T00:00:00Z");
        private final Instant updatedAt = Instant.parse("2026-04-01T10:00:00Z");

        ProfileBuilder status(RealnameStatus status) {
            this.status = status;
            return this;
        }

        ProfileBuilder realNameEnc(byte[] v) {
            this.realNameEnc = v;
            return this;
        }

        ProfileBuilder idCardNoEnc(byte[] v) {
            this.idCardNoEnc = v;
            return this;
        }

        ProfileBuilder verifiedAt(Instant v) {
            this.verifiedAt = v;
            return this;
        }

        ProfileBuilder failedReason(FailedReason v) {
            this.failedReason = v;
            return this;
        }

        ProfileBuilder failedAt(Instant v) {
            this.failedAt = v;
            return this;
        }

        RealnameProfile build() {
            return RealnameProfile.reconstitute(
                    100L,
                    ACCOUNT_ID,
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
