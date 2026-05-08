package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.application.command.RefreshTokenCommand;
import com.mbw.account.application.result.RefreshTokenResult;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.model.RefreshTokenHash;
import com.mbw.account.domain.model.RefreshTokenRecord;
import com.mbw.account.domain.model.RefreshTokenRecordId;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.RefreshTokenHasher;
import com.mbw.account.domain.service.TokenIssuer;
import com.mbw.shared.web.RateLimitService;
import com.mbw.shared.web.RateLimitedException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefreshTokenUseCaseTest {

    private static final String OLD_RAW_TOKEN = "old-raw-token-value";
    private static final String NEW_RAW_TOKEN = "new-raw-token-value";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final long ACCOUNT_ID = 42L;
    private static final RefreshTokenHash OLD_HASH = RefreshTokenHasher.hash(OLD_RAW_TOKEN);
    private static final Instant CREATED_AT = Instant.parse("2026-04-29T01:00:00Z");

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TokenIssuer tokenIssuer;

    @Mock
    private TransactionTemplate transactionTemplate;

    private RefreshTokenUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RefreshTokenUseCase(
                rateLimitService, refreshTokenRepository, accountRepository, tokenIssuer, transactionTemplate);

        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });

        // Default success path
        when(tokenIssuer.signAccess(any(AccountId.class), any())).thenReturn("access-jwt");
        when(tokenIssuer.signRefresh()).thenReturn(NEW_RAW_TOKEN);
        when(refreshTokenRepository.findByTokenHash(any(RefreshTokenHash.class)))
                .thenReturn(Optional.of(activeOldRecord()));
        when(accountRepository.findById(any(AccountId.class))).thenReturn(Optional.of(activeAccount()));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokenRepository.revoke(any(), any())).thenReturn(1); // happy: revoke wins
    }

    @Test
    void should_rotate_and_return_new_token_pair_on_happy_path() {
        RefreshTokenResult result = useCase.execute(new RefreshTokenCommand(OLD_RAW_TOKEN, CLIENT_IP));

        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.accessToken()).isEqualTo("access-jwt");
        assertThat(result.refreshToken()).isEqualTo(NEW_RAW_TOKEN);

        verify(rateLimitService, times(2)).consumeOrThrow(any(), any()); // IP + token-hash
        verify(refreshTokenRepository).findByTokenHash(OLD_HASH);
        verify(refreshTokenRepository, times(1)).save(any()); // new record persisted
        verify(refreshTokenRepository).revoke(any(RefreshTokenRecordId.class), any(Instant.class));
        verify(tokenIssuer).signAccess(any(AccountId.class), any());
        verify(tokenIssuer).signRefresh();
    }

    @Test
    void should_throw_RateLimitedException_when_ip_throttled() {
        doThrow(new RateLimitedException("refresh:" + CLIENT_IP, Duration.ofSeconds(60)))
                .when(rateLimitService)
                .consumeOrThrow(any(), any());

        assertThatThrownBy(() -> useCase.execute(new RefreshTokenCommand(OLD_RAW_TOKEN, CLIENT_IP)))
                .isInstanceOf(RateLimitedException.class);

        verify(refreshTokenRepository, never()).findByTokenHash(any());
    }

    @Test
    void should_throw_InvalidCredentials_when_token_not_in_db() {
        when(refreshTokenRepository.findByTokenHash(any(RefreshTokenHash.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new RefreshTokenCommand(OLD_RAW_TOKEN, CLIENT_IP)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revoke(any(), any());
    }

    @Test
    void should_throw_InvalidCredentials_when_token_expired() {
        RefreshTokenRecord expired = RefreshTokenRecord.reconstitute(
                new RefreshTokenRecordId(1L),
                OLD_HASH,
                new AccountId(ACCOUNT_ID),
                CREATED_AT.minusSeconds(1),
                /* revokedAt= */ null,
                CREATED_AT.minusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(any(RefreshTokenHash.class)))
                .thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> useCase.execute(new RefreshTokenCommand(OLD_RAW_TOKEN, CLIENT_IP)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revoke(any(), any());
    }

    @Test
    void should_throw_InvalidCredentials_when_token_already_revoked() {
        RefreshTokenRecord revoked = RefreshTokenRecord.reconstitute(
                new RefreshTokenRecordId(1L),
                OLD_HASH,
                new AccountId(ACCOUNT_ID),
                Instant.now().plusSeconds(3600),
                /* revokedAt= */ Instant.now().minusSeconds(60),
                CREATED_AT);
        when(refreshTokenRepository.findByTokenHash(any(RefreshTokenHash.class)))
                .thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> useCase.execute(new RefreshTokenCommand(OLD_RAW_TOKEN, CLIENT_IP)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void should_throw_InvalidCredentials_when_account_not_found() {
        when(accountRepository.findById(any(AccountId.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new RefreshTokenCommand(OLD_RAW_TOKEN, CLIENT_IP)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revoke(any(), any());
    }

    @Test
    void should_throw_InvalidCredentials_when_account_FROZEN() {
        Account frozen = Account.reconstitute(
                new AccountId(ACCOUNT_ID),
                new PhoneNumber("+8613800138000"),
                AccountStatus.FROZEN,
                CREATED_AT,
                CREATED_AT,
                null);
        when(accountRepository.findById(any(AccountId.class))).thenReturn(Optional.of(frozen));

        assertThatThrownBy(() -> useCase.execute(new RefreshTokenCommand(OLD_RAW_TOKEN, CLIENT_IP)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void should_propagate_token_signing_failure_so_tx_rolls_back() {
        when(tokenIssuer.signAccess(any(AccountId.class), any()))
                .thenThrow(new RuntimeException("token signing failed"));

        assertThatThrownBy(() -> useCase.execute(new RefreshTokenCommand(OLD_RAW_TOKEN, CLIENT_IP)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("token signing failed");
    }

    private static RefreshTokenRecord activeOldRecord() {
        return RefreshTokenRecord.reconstitute(
                new RefreshTokenRecordId(1L),
                OLD_HASH,
                new AccountId(ACCOUNT_ID),
                Instant.now().plusSeconds(30L * 24 * 3600),
                /* revokedAt= */ null,
                CREATED_AT);
    }

    private static Account activeAccount() {
        return Account.reconstitute(
                new AccountId(ACCOUNT_ID),
                new PhoneNumber("+8613800138000"),
                AccountStatus.ACTIVE,
                CREATED_AT,
                CREATED_AT,
                null);
    }
}
