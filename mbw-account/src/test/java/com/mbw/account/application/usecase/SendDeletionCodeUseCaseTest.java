package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.application.command.SendDeletionCodeCommand;
import com.mbw.account.domain.exception.AccountInactiveException;
import com.mbw.account.domain.exception.AccountNotFoundException;
import com.mbw.account.domain.model.Account;
import com.mbw.account.domain.model.AccountId;
import com.mbw.account.domain.model.AccountSmsCode;
import com.mbw.account.domain.model.AccountSmsCodeId;
import com.mbw.account.domain.model.AccountSmsCodePurpose;
import com.mbw.account.domain.model.AccountStatus;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.account.domain.repository.AccountSmsCodeRepository;
import com.mbw.shared.api.sms.SmsClient;
import com.mbw.shared.api.sms.SmsSendException;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SendDeletionCodeUseCaseTest {

    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final String CLIENT_IP = "203.0.113.7";
    private static final Instant NOW = Instant.parse("2026-05-06T10:00:00Z");
    private static final PhoneNumber PHONE = new PhoneNumber("+8613800138000");

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountSmsCodeRepository smsCodeRepository;

    @Mock
    private SmsClient smsClient;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private SendDeletionCodeUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SendDeletionCodeUseCase(rateLimitService, accountRepository, smsCodeRepository, smsClient, clock);
    }

    @Test
    void should_send_code_and_persist_when_account_is_ACTIVE() {
        Account account = Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.ACTIVE, NOW, NOW);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        AccountSmsCode persistedCode = AccountSmsCode.reconstitute(
                new AccountSmsCodeId(1L),
                ACCOUNT_ID,
                "aabbcc",
                AccountSmsCodePurpose.DELETE_ACCOUNT,
                NOW.plus(Duration.ofMinutes(10)),
                null,
                NOW);
        when(smsCodeRepository.save(any())).thenReturn(persistedCode);

        useCase.execute(new SendDeletionCodeCommand(ACCOUNT_ID, CLIENT_IP));

        verify(rateLimitService).consumeOrThrow(eq("delete-code:account:42"), any());
        verify(rateLimitService).consumeOrThrow(eq("delete-code:ip:" + CLIENT_IP), any());
        verify(smsCodeRepository).save(any(AccountSmsCode.class));
        verify(smsClient).send(eq(PHONE.e164()), eq(SendDeletionCodeUseCase.SMS_TEMPLATE), any());
    }

    @Test
    void should_throw_RateLimitedException_when_account_throttled() {
        doThrow(new RateLimitedException("delete-code:account:42", Duration.ofSeconds(55)))
                .when(rateLimitService)
                .consumeOrThrow(eq("delete-code:account:42"), any());

        assertThatThrownBy(() -> useCase.execute(new SendDeletionCodeCommand(ACCOUNT_ID, CLIENT_IP)))
                .isInstanceOf(RateLimitedException.class);

        verify(accountRepository, never()).findById(any());
        verify(smsCodeRepository, never()).save(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_throw_RateLimitedException_when_ip_throttled() {
        // account-key must pass (no-op) so the IP check is reached
        doThrow(new RateLimitedException("delete-code:ip:" + CLIENT_IP, Duration.ofSeconds(30)))
                .when(rateLimitService)
                .consumeOrThrow(eq("delete-code:ip:" + CLIENT_IP), any());

        assertThatThrownBy(() -> useCase.execute(new SendDeletionCodeCommand(ACCOUNT_ID, CLIENT_IP)))
                .isInstanceOf(RateLimitedException.class);

        verify(accountRepository, never()).findById(any());
        verify(smsCodeRepository, never()).save(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_throw_AccountInactiveException_when_account_FROZEN() {
        Account account = Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.FROZEN, NOW, NOW);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> useCase.execute(new SendDeletionCodeCommand(ACCOUNT_ID, CLIENT_IP)))
                .isInstanceOf(AccountInactiveException.class);

        verify(smsCodeRepository, never()).save(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_throw_AccountInactiveException_when_account_ANONYMIZED() {
        Account account = Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.ANONYMIZED, NOW, NOW);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> useCase.execute(new SendDeletionCodeCommand(ACCOUNT_ID, CLIENT_IP)))
                .isInstanceOf(AccountInactiveException.class);

        verify(smsCodeRepository, never()).save(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_throw_SmsSendException_when_SmsClient_throws() {
        Account account = Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.ACTIVE, NOW, NOW);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        AccountSmsCode persistedCode = AccountSmsCode.reconstitute(
                new AccountSmsCodeId(1L),
                ACCOUNT_ID,
                "aabbcc",
                AccountSmsCodePurpose.DELETE_ACCOUNT,
                NOW.plus(Duration.ofMinutes(10)),
                null,
                NOW);
        when(smsCodeRepository.save(any())).thenReturn(persistedCode);
        doThrow(new SmsSendException("gateway error")).when(smsClient).send(any(), any(), any());

        assertThatThrownBy(() -> useCase.execute(new SendDeletionCodeCommand(ACCOUNT_ID, CLIENT_IP)))
                .isInstanceOf(SmsSendException.class);
    }

    @Test
    void should_throw_AccountNotFoundException_when_account_not_found() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new SendDeletionCodeCommand(ACCOUNT_ID, CLIENT_IP)))
                .isInstanceOf(AccountNotFoundException.class);

        verify(smsCodeRepository, never()).save(any());
        verify(smsClient, never()).send(any(), any(), any());
    }
}
