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

import com.mbw.account.application.command.LoginByPhoneSmsCommand;
import com.mbw.account.application.result.LoginByPhoneSmsResult;
import com.mbw.account.domain.exception.InvalidCredentialsException;
import com.mbw.account.domain.exception.InvalidPhoneFormatException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.service.TokenIssuer;
import com.mbw.shared.api.sms.AttemptOutcome;
import com.mbw.shared.api.sms.SmsCodeService;
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
class LoginByPhoneSmsUseCaseTest {

    private static final String PHONE = "+8613800138000";
    private static final String CODE = "123456";
    private static final long ACCOUNT_ID = 42L;
    private static final Instant CREATED_AT = Instant.parse("2026-04-29T01:00:00Z");

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private SmsCodeService smsCodeService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TokenIssuer tokenIssuer;

    @Mock
    private TransactionTemplate transactionTemplate;

    private LoginByPhoneSmsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new LoginByPhoneSmsUseCase(
                rateLimitService, smsCodeService, accountRepository, tokenIssuer, transactionTemplate);

        // Pass-through transaction template
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });

        // Default: code matches, account ACTIVE, tokens sign cleanly
        when(smsCodeService.verify(eq(PHONE), eq(CODE))).thenReturn(new AttemptOutcome(true, 0, false));
        when(accountRepository.findByPhone(any())).thenReturn(Optional.of(activeAccount()));
        when(tokenIssuer.signAccess(any(AccountId.class))).thenReturn("access-jwt");
        when(tokenIssuer.signRefresh()).thenReturn("refresh-token");
    }

    @Test
    void should_return_tokens_when_phone_registered_and_code_valid() {
        LoginByPhoneSmsResult result = useCase.execute(new LoginByPhoneSmsCommand(PHONE, CODE));

        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.accessToken()).isEqualTo("access-jwt");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");

        verify(rateLimitService).consumeOrThrow(eq("login:" + PHONE), any());
        verify(smsCodeService).verify(PHONE, CODE);
        verify(accountRepository).findByPhone(any(PhoneNumber.class));
        verify(accountRepository).updateLastLoginAt(eq(new AccountId(ACCOUNT_ID)), any(Instant.class));
        verify(tokenIssuer, times(1)).signAccess(any(AccountId.class));
        verify(tokenIssuer, times(1)).signRefresh();
    }

    @Test
    void should_throw_RateLimitedException_when_phone_throttled() {
        doThrow(new RateLimitedException("login:" + PHONE, Duration.ofHours(1)))
                .when(rateLimitService)
                .consumeOrThrow(eq("login:" + PHONE), any());

        assertThatThrownBy(() -> useCase.execute(new LoginByPhoneSmsCommand(PHONE, CODE)))
                .isInstanceOf(RateLimitedException.class);

        verify(smsCodeService, never()).verify(any(), any());
        verify(accountRepository, never()).findByPhone(any());
    }

    @Test
    void should_throw_InvalidCredentials_when_code_wrong() {
        when(smsCodeService.verify(eq(PHONE), eq(CODE))).thenReturn(new AttemptOutcome(false, 1, false));

        assertThatThrownBy(() -> useCase.execute(new LoginByPhoneSmsCommand(PHONE, CODE)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(accountRepository, never()).findByPhone(any());
        verify(accountRepository, never()).updateLastLoginAt(any(), any());
        verify(tokenIssuer, never()).signAccess(any());
    }

    @Test
    void should_throw_InvalidCredentials_when_phone_not_registered() {
        when(accountRepository.findByPhone(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new LoginByPhoneSmsCommand(PHONE, CODE)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(smsCodeService).verify(PHONE, CODE); // code consumed even on unregistered to keep timing equal
        verify(accountRepository, never()).updateLastLoginAt(any(), any());
        verify(tokenIssuer, never()).signAccess(any());
    }

    @Test
    void should_throw_InvalidCredentials_when_account_FROZEN() {
        Account frozen = Account.reconstitute(
                new AccountId(ACCOUNT_ID), new PhoneNumber(PHONE), AccountStatus.FROZEN, CREATED_AT, CREATED_AT, null);
        when(accountRepository.findByPhone(any())).thenReturn(Optional.of(frozen));

        assertThatThrownBy(() -> useCase.execute(new LoginByPhoneSmsCommand(PHONE, CODE)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(accountRepository, never()).updateLastLoginAt(any(), any());
        verify(tokenIssuer, never()).signAccess(any());
    }

    @Test
    void should_propagate_InvalidPhoneFormat_for_bad_phone() {
        assertThatThrownBy(() -> useCase.execute(new LoginByPhoneSmsCommand("+12025550100", CODE)))
                .isInstanceOf(InvalidPhoneFormatException.class);

        verify(rateLimitService, never()).consumeOrThrow(any(), any());
        verify(smsCodeService, never()).verify(any(), any());
    }

    @Test
    void should_propagate_token_signing_failure_so_tx_rolls_back() {
        when(tokenIssuer.signAccess(any(AccountId.class))).thenThrow(new RuntimeException("token signing failed"));

        assertThatThrownBy(() -> useCase.execute(new LoginByPhoneSmsCommand(PHONE, CODE)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("token signing failed");

        // updateLastLoginAt was called (inside the tx) — but transaction
        // rollback in production would undo it. The unit test verifies
        // only the call ordering; tx semantics are exercised in IT.
        verify(accountRepository).updateLastLoginAt(any(), any(Instant.class));
    }

    private static Account activeAccount() {
        return Account.reconstitute(
                new AccountId(ACCOUNT_ID), new PhoneNumber(PHONE), AccountStatus.ACTIVE, CREATED_AT, CREATED_AT, null);
    }
}
