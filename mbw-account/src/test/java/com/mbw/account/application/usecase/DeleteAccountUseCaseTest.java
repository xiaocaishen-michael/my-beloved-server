package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.application.command.DeleteAccountCommand;
import com.mbw.account.domain.exception.AccountNotFoundException;
import com.mbw.account.domain.exception.InvalidDeletionCodeException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountSmsCode;
import com.mbw.account.domain.model.AccountSmsCodeId;
import com.mbw.account.domain.model.AccountSmsCodePurpose;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.AccountSmsCodeRepository;
import com.mbw.account.domain.repository.RefreshTokenRepository;
import com.mbw.shared.web.RateLimitService;
import com.mbw.shared.web.RateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeleteAccountUseCaseTest {

    // SHA-256 hex of "123456"
    private static final String CODE_PLAINTEXT = "123456";
    private static final String CODE_HASH = "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92";

    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final String CLIENT_IP = "203.0.113.7";
    private static final Instant NOW = Instant.parse("2026-05-06T10:00:00Z");
    private static final PhoneNumber PHONE = new PhoneNumber("+8613800138000");

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private AccountSmsCodeRepository smsCodeRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private DeleteAccountUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new DeleteAccountUseCase(
                rateLimitService, smsCodeRepository, accountRepository, refreshTokenRepository, eventPublisher, clock);
    }

    @Test
    void should_transition_to_FROZEN_and_revoke_tokens_and_publish_event_when_code_valid() {
        AccountSmsCode code = activeCode(CODE_HASH, NOW.plusSeconds(300));
        when(smsCodeRepository.findActiveByPurposeAndAccountId(AccountSmsCodePurpose.DELETE_ACCOUNT, ACCOUNT_ID, NOW))
                .thenReturn(Optional.of(code));
        Account account = Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.ACTIVE, NOW, NOW);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);
        when(refreshTokenRepository.revokeAllForAccount(ACCOUNT_ID, NOW)).thenReturn(2);

        useCase.execute(new DeleteAccountCommand(ACCOUNT_ID, CODE_PLAINTEXT, CLIENT_IP));

        verify(smsCodeRepository).markUsed(code.id(), NOW);
        verify(accountRepository).save(account);
        verify(refreshTokenRepository).revokeAllForAccount(ACCOUNT_ID, NOW);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void should_throw_RateLimitedException_when_account_throttled() {
        doThrow(new RateLimitedException("delete-submit:account:42", Duration.ofSeconds(55)))
                .when(rateLimitService)
                .consumeOrThrow(eq("delete-submit:account:42"), any());

        assertThatThrownBy(() -> useCase.execute(new DeleteAccountCommand(ACCOUNT_ID, CODE_PLAINTEXT, CLIENT_IP)))
                .isInstanceOf(RateLimitedException.class);

        verify(smsCodeRepository, never()).findActiveByPurposeAndAccountId(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_RateLimitedException_when_ip_throttled() {
        doThrow(new RateLimitedException("delete-submit:ip:" + CLIENT_IP, Duration.ofSeconds(30)))
                .when(rateLimitService)
                .consumeOrThrow(eq("delete-submit:ip:" + CLIENT_IP), any());

        assertThatThrownBy(() -> useCase.execute(new DeleteAccountCommand(ACCOUNT_ID, CODE_PLAINTEXT, CLIENT_IP)))
                .isInstanceOf(RateLimitedException.class);

        verify(smsCodeRepository, never()).findActiveByPurposeAndAccountId(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_InvalidDeletionCodeException_when_code_not_found() {
        when(smsCodeRepository.findActiveByPurposeAndAccountId(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new DeleteAccountCommand(ACCOUNT_ID, CODE_PLAINTEXT, CLIENT_IP)))
                .isInstanceOf(InvalidDeletionCodeException.class);

        verify(smsCodeRepository, never()).markUsed(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_InvalidDeletionCodeException_when_code_hash_mismatch() {
        AccountSmsCode code = activeCode("wronghash", NOW.plusSeconds(300));
        when(smsCodeRepository.findActiveByPurposeAndAccountId(any(), any(), any()))
                .thenReturn(Optional.of(code));

        assertThatThrownBy(() -> useCase.execute(new DeleteAccountCommand(ACCOUNT_ID, CODE_PLAINTEXT, CLIENT_IP)))
                .isInstanceOf(InvalidDeletionCodeException.class);

        verify(smsCodeRepository, never()).markUsed(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_InvalidDeletionCodeException_when_code_expired() {
        // expires_at in the past — isActive returns false despite repo returning the record
        AccountSmsCode code = activeCode(CODE_HASH, NOW.minusSeconds(1));
        when(smsCodeRepository.findActiveByPurposeAndAccountId(any(), any(), any()))
                .thenReturn(Optional.of(code));

        assertThatThrownBy(() -> useCase.execute(new DeleteAccountCommand(ACCOUNT_ID, CODE_PLAINTEXT, CLIENT_IP)))
                .isInstanceOf(InvalidDeletionCodeException.class);

        verify(smsCodeRepository, never()).markUsed(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_throw_AccountNotFoundException_when_account_not_found_concurrently() {
        AccountSmsCode code = activeCode(CODE_HASH, NOW.plusSeconds(300));
        when(smsCodeRepository.findActiveByPurposeAndAccountId(any(), any(), any()))
                .thenReturn(Optional.of(code));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new DeleteAccountCommand(ACCOUNT_ID, CODE_PLAINTEXT, CLIENT_IP)))
                .isInstanceOf(AccountNotFoundException.class);

        verify(smsCodeRepository).markUsed(code.id(), NOW);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void should_rollback_when_revokeAllForAccount_throws() {
        AccountSmsCode code = activeCode(CODE_HASH, NOW.plusSeconds(300));
        when(smsCodeRepository.findActiveByPurposeAndAccountId(any(), any(), any()))
                .thenReturn(Optional.of(code));
        Account account = Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.ACTIVE, NOW, NOW);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);
        doThrow(new RuntimeException("DB error")).when(refreshTokenRepository).revokeAllForAccount(any(), any());

        assertThatThrownBy(() -> useCase.execute(new DeleteAccountCommand(ACCOUNT_ID, CODE_PLAINTEXT, CLIENT_IP)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB error");

        verify(eventPublisher, never()).publishEvent(any());
    }

    private static AccountSmsCode activeCode(String hash, Instant expiresAt) {
        return AccountSmsCode.reconstitute(
                new AccountSmsCodeId(1L),
                ACCOUNT_ID,
                hash,
                AccountSmsCodePurpose.DELETE_ACCOUNT,
                expiresAt,
                /* usedAt= */ null,
                NOW);
    }
}
