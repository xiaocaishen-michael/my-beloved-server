package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.application.command.SendCancelDeletionCodeCommand;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SendCancelDeletionCodeUseCaseTest {

    private static final AccountId ACCOUNT_ID = new AccountId(42L);
    private static final String PHONE_RAW = "+8613800138000";
    private static final PhoneNumber PHONE = new PhoneNumber(PHONE_RAW);
    private static final String CLIENT_IP = "203.0.113.7";
    private static final Instant NOW = Instant.parse("2026-05-06T10:00:00Z");

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountSmsCodeRepository smsCodeRepository;

    @Mock
    private SmsClient smsClient;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private SendCancelDeletionCodeUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SendCancelDeletionCodeUseCase(
                rateLimitService, accountRepository, smsCodeRepository, smsClient, clock);
    }

    private SendCancelDeletionCodeCommand cmd() {
        return new SendCancelDeletionCodeCommand(PHONE_RAW, CLIENT_IP);
    }

    private static Account frozenAccount(Instant freezeUntil) {
        return Account.reconstitute(
                ACCOUNT_ID,
                PHONE,
                AccountStatus.FROZEN,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60),
                null,
                null,
                freezeUntil);
    }

    @Test
    void should_send_code_when_account_FROZEN_in_grace() {
        Instant freezeUntil = NOW.plus(Duration.ofDays(10));
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.of(frozenAccount(freezeUntil)));
        AccountSmsCode persistedCode = AccountSmsCode.reconstitute(
                new AccountSmsCodeId(1L),
                ACCOUNT_ID,
                "aabbcc",
                AccountSmsCodePurpose.CANCEL_DELETION,
                NOW.plus(Duration.ofMinutes(10)),
                null,
                NOW);
        when(smsCodeRepository.save(any())).thenReturn(persistedCode);

        useCase.execute(cmd());

        verify(rateLimitService).consumeOrThrow(eq("cancel-code:phone:" + sha256(PHONE_RAW)), any());
        verify(rateLimitService).consumeOrThrow(eq("cancel-code:ip:" + CLIENT_IP), any());

        ArgumentCaptor<AccountSmsCode> captor = ArgumentCaptor.forClass(AccountSmsCode.class);
        verify(smsCodeRepository).save(captor.capture());
        assertThat(captor.getValue().purpose()).isEqualTo(AccountSmsCodePurpose.CANCEL_DELETION);
        assertThat(captor.getValue().accountId()).isEqualTo(ACCOUNT_ID);

        verify(smsClient).send(eq(PHONE.e164()), eq(SendCancelDeletionCodeUseCase.SMS_TEMPLATE), any());
    }

    @Test
    void should_dummy_response_when_phone_not_found() {
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.empty());

        useCase.execute(cmd());

        verify(smsCodeRepository, never()).save(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_dummy_response_when_account_ACTIVE() {
        Account active = Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.ACTIVE, NOW, NOW);
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.of(active));

        useCase.execute(cmd());

        verify(smsCodeRepository, never()).save(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_dummy_response_when_account_ANONYMIZED() {
        Account anonymized = Account.reconstitute(ACCOUNT_ID, PHONE, AccountStatus.ANONYMIZED, NOW, NOW);
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.of(anonymized));

        useCase.execute(cmd());

        verify(smsCodeRepository, never()).save(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_dummy_response_when_account_FROZEN_grace_expired() {
        Instant freezeUntil = NOW.minusSeconds(1); // grace expired
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.of(frozenAccount(freezeUntil)));

        useCase.execute(cmd());

        verify(smsCodeRepository, never()).save(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_throw_RateLimitedException_when_phone_throttled() {
        doThrow(new RateLimitedException("cancel-code:phone:" + sha256(PHONE_RAW), Duration.ofSeconds(55)))
                .when(rateLimitService)
                .consumeOrThrow(eq("cancel-code:phone:" + sha256(PHONE_RAW)), any());

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(RateLimitedException.class);

        verify(accountRepository, never()).findByPhone(any());
        verify(smsCodeRepository, never()).save(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_throw_RateLimitedException_when_ip_throttled() {
        doThrow(new RateLimitedException("cancel-code:ip:" + CLIENT_IP, Duration.ofSeconds(30)))
                .when(rateLimitService)
                .consumeOrThrow(eq("cancel-code:ip:" + CLIENT_IP), any());

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(RateLimitedException.class);

        verify(accountRepository, never()).findByPhone(any());
        verify(smsCodeRepository, never()).save(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_throw_SmsSendException_when_SmsClient_fails_on_FROZEN_match() {
        Instant freezeUntil = NOW.plus(Duration.ofDays(10));
        when(accountRepository.findByPhone(PHONE)).thenReturn(Optional.of(frozenAccount(freezeUntil)));
        AccountSmsCode persistedCode = AccountSmsCode.reconstitute(
                new AccountSmsCodeId(1L),
                ACCOUNT_ID,
                "aabbcc",
                AccountSmsCodePurpose.CANCEL_DELETION,
                NOW.plus(Duration.ofMinutes(10)),
                null,
                NOW);
        when(smsCodeRepository.save(any())).thenReturn(persistedCode);
        doThrow(new SmsSendException("gateway error")).when(smsClient).send(any(), any(), any());

        assertThatThrownBy(() -> useCase.execute(cmd())).isInstanceOf(SmsSendException.class);
    }

    private static String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
