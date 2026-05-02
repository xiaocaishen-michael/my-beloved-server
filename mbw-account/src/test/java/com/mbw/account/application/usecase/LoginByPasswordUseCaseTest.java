package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.application.command.LoginByPasswordCommand;
import com.mbw.account.application.result.LoginByPasswordResult;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.exception.InvalidPhoneFormatException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PasswordCredential;
import com.mbw.account.domain.model.PasswordHash;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.CredentialRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.account.domain.service.PasswordHasher;
import com.mbw.account.domain.service.TimingDefenseExecutor;
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
class LoginByPasswordUseCaseTest {

    private static final String PHONE = "+8613800138000";
    private static final String PASSWORD = "MyStrongP4ss";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final long ACCOUNT_ID = 42L;
    private static final Instant CREATED_AT = Instant.parse("2026-04-29T01:00:00Z");
    private static final PasswordHash STORED_HASH =
            new PasswordHash("$2a$08$abcdefghijklmnopqrstuOhwLg7BiUOlJBzm/dW0oLhI5e5FKWJP2");

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CredentialRepository credentialRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private TokenIssuer tokenIssuer;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private LoginByPasswordUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new LoginByPasswordUseCase(
                rateLimitService,
                accountRepository,
                credentialRepository,
                passwordHasher,
                tokenIssuer,
                refreshTokenRepository,
                transactionTemplate);

        // Pass-through transaction template
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });

        // Default success path: account ACTIVE, password set, hash matches, tokens sign
        when(accountRepository.findByPhone(any())).thenReturn(Optional.of(activeAccount()));
        when(credentialRepository.findPasswordCredentialByAccountId(any()))
                .thenReturn(Optional.of(new PasswordCredential(new AccountId(ACCOUNT_ID), STORED_HASH, CREATED_AT)));
        when(passwordHasher.matches(any(), any())).thenReturn(true);
        when(tokenIssuer.signAccess(any(AccountId.class))).thenReturn("access-jwt");
        when(tokenIssuer.signRefresh()).thenReturn("refresh-token");
    }

    @Test
    void should_return_tokens_when_phone_password_match_active_account() {
        LoginByPasswordResult result = useCase.execute(new LoginByPasswordCommand(PHONE, PASSWORD, CLIENT_IP));

        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.accessToken()).isEqualTo("access-jwt");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");

        verify(rateLimitService).consumeOrThrow(eq("login:" + PHONE), any());
        verify(rateLimitService).consumeOrThrow(eq("auth:" + CLIENT_IP), any());
        verify(passwordHasher).matches(eq(PASSWORD), eq(STORED_HASH));
        verify(accountRepository).updateLastLoginAt(eq(new AccountId(ACCOUNT_ID)), any(Instant.class));
        verify(tokenIssuer, times(1)).signAccess(any(AccountId.class));
        verify(tokenIssuer, times(1)).signRefresh();
    }

    @Test
    void should_throw_RateLimitedException_when_login_phone_throttled() {
        doThrow(new RateLimitedException("login:" + PHONE, Duration.ofHours(1)))
                .when(rateLimitService)
                .consumeOrThrow(eq("login:" + PHONE), any());

        assertThatThrownBy(() -> useCase.execute(new LoginByPasswordCommand(PHONE, PASSWORD, CLIENT_IP)))
                .isInstanceOf(RateLimitedException.class);

        verify(passwordHasher, never()).matches(any(), any());
    }

    @Test
    void should_throw_RateLimitedException_when_auth_ip_throttled() {
        doThrow(new RateLimitedException("auth:" + CLIENT_IP, Duration.ofHours(1)))
                .when(rateLimitService)
                .consumeOrThrow(eq("auth:" + CLIENT_IP), any());

        assertThatThrownBy(() -> useCase.execute(new LoginByPasswordCommand(PHONE, PASSWORD, CLIENT_IP)))
                .isInstanceOf(RateLimitedException.class);

        verify(passwordHasher, never()).matches(any(), any());
    }

    @Test
    void should_throw_InvalidCredentials_when_password_wrong() {
        when(passwordHasher.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(new LoginByPasswordCommand(PHONE, PASSWORD, CLIENT_IP)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordHasher).matches(eq(PASSWORD), eq(STORED_HASH));
        verify(accountRepository, never()).updateLastLoginAt(any(), any());
        verify(tokenIssuer, never()).signAccess(any());
    }

    @Test
    void should_throw_InvalidCredentials_when_phone_not_registered() {
        when(accountRepository.findByPhone(any())).thenReturn(Optional.empty());
        when(passwordHasher.matches(any(), any())).thenReturn(false); // BCrypt against DUMMY_HASH

        assertThatThrownBy(() -> useCase.execute(new LoginByPasswordCommand(PHONE, PASSWORD, CLIENT_IP)))
                .isInstanceOf(InvalidCredentialsException.class);

        // hashLookup returns DUMMY_HASH; BCrypt verify still ran (timing-equal)
        verify(passwordHasher).matches(eq(PASSWORD), eq(TimingDefenseExecutor.DUMMY_HASH));
        verify(accountRepository, never()).updateLastLoginAt(any(), any());
    }

    @Test
    void should_throw_InvalidCredentials_when_password_not_set() {
        when(credentialRepository.findPasswordCredentialByAccountId(any())).thenReturn(Optional.empty());
        when(passwordHasher.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(new LoginByPasswordCommand(PHONE, PASSWORD, CLIENT_IP)))
                .isInstanceOf(InvalidCredentialsException.class);

        // password not set → hashLookup falls back to DUMMY_HASH; verify still ran
        verify(passwordHasher).matches(eq(PASSWORD), eq(TimingDefenseExecutor.DUMMY_HASH));
        verify(accountRepository, never()).updateLastLoginAt(any(), any());
    }

    @Test
    void should_throw_InvalidCredentials_when_account_FROZEN() {
        Account frozen = Account.reconstitute(
                new AccountId(ACCOUNT_ID), new PhoneNumber(PHONE), AccountStatus.FROZEN, CREATED_AT, CREATED_AT, null);
        when(accountRepository.findByPhone(any())).thenReturn(Optional.of(frozen));
        when(passwordHasher.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(new LoginByPasswordCommand(PHONE, PASSWORD, CLIENT_IP)))
                .isInstanceOf(InvalidCredentialsException.class);

        // FROZEN → hashLookup returns DUMMY_HASH; PasswordCredential lookup is skipped
        verify(passwordHasher).matches(eq(PASSWORD), eq(TimingDefenseExecutor.DUMMY_HASH));
        verify(credentialRepository, never()).findPasswordCredentialByAccountId(any());
        verify(accountRepository, never()).updateLastLoginAt(any(), any());
    }

    @Test
    void should_propagate_token_signing_failure_so_tx_rolls_back() {
        when(tokenIssuer.signAccess(any(AccountId.class))).thenThrow(new RuntimeException("token signing failed"));

        assertThatThrownBy(() -> useCase.execute(new LoginByPasswordCommand(PHONE, PASSWORD, CLIENT_IP)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("token signing failed");
    }

    @Test
    void should_propagate_InvalidPhoneFormat_for_bad_phone() {
        assertThatThrownBy(() -> useCase.execute(new LoginByPasswordCommand("+12025550100", PASSWORD, CLIENT_IP)))
                .isInstanceOf(InvalidPhoneFormatException.class);

        verify(rateLimitService, never()).consumeOrThrow(any(), any());
        verify(passwordHasher, never()).matches(any(), any());
    }

    private static Account activeAccount() {
        return Account.reconstitute(
                new AccountId(ACCOUNT_ID), new PhoneNumber(PHONE), AccountStatus.ACTIVE, CREATED_AT, CREATED_AT, null);
    }
}
