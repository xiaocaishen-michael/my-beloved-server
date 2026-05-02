package com.mbw.account.application.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mbw.account.application.command.RequestSmsCodeCommand;
import com.mbw.account.application.command.SmsCodePurpose;
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.shared.api.sms.SmsClient;
import com.mbw.shared.api.sms.SmsCodeService;
import com.mbw.shared.api.sms.SmsSendException;
import com.mbw.shared.web.RateLimitService;
import com.mbw.shared.web.RateLimitedException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RequestSmsCodeUseCaseTest {

    private static final String PHONE = "+8613800138000";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String TEMPLATE_C_ID = "SMS_LOGIN_UNREGISTERED_C";

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private SmsCodeService smsCodeService;

    @Mock
    private SmsClient smsClient;

    private RequestSmsCodeUseCase useCase(String templateCId) {
        return new RequestSmsCodeUseCase(rateLimitService, accountRepository, smsCodeService, smsClient, templateCId);
    }

    private RequestSmsCodeUseCase useCaseTemplateCUnavailable() {
        return useCase("");
    }

    private RequestSmsCodeUseCase useCaseTemplateCAvailable() {
        return useCase(TEMPLATE_C_ID);
    }

    private RequestSmsCodeCommand registerCmd() {
        return new RequestSmsCodeCommand(PHONE, CLIENT_IP, SmsCodePurpose.REGISTER);
    }

    private RequestSmsCodeCommand loginCmd() {
        return new RequestSmsCodeCommand(PHONE, CLIENT_IP, SmsCodePurpose.LOGIN);
    }

    @Test
    void unregistered_phone_should_generate_code_and_send_Template_A_when_purpose_REGISTER() {
        when(accountRepository.existsByPhone(any(PhoneNumber.class))).thenReturn(false);
        when(smsCodeService.generateAndStore(PHONE)).thenReturn("123456");

        useCaseTemplateCUnavailable().execute(registerCmd());

        verify(rateLimitService).consumeOrThrow(eq("sms-60s:" + PHONE), eq(RequestSmsCodeUseCase.PER_PHONE_60S));
        verify(rateLimitService).consumeOrThrow(eq("sms-24h:" + PHONE), eq(RequestSmsCodeUseCase.PER_PHONE_24H));
        verify(rateLimitService).consumeOrThrow(eq("sms-ip:" + CLIENT_IP), eq(RequestSmsCodeUseCase.PER_IP_24H));
        verify(smsCodeService).generateAndStore(PHONE);
        verify(smsClient).send(eq(PHONE), eq(RequestSmsCodeUseCase.SMS_TEMPLATE_REGISTER), argThat(m -> "123456"
                .equals(m.get("code"))));
    }

    @Test
    void registered_phone_should_send_Template_B_without_code_when_purpose_REGISTER() {
        when(accountRepository.existsByPhone(any(PhoneNumber.class))).thenReturn(true);

        useCaseTemplateCUnavailable().execute(registerCmd());

        verify(smsClient)
                .send(
                        eq(PHONE),
                        eq(RequestSmsCodeUseCase.SMS_TEMPLATE_ALREADY_REGISTERED),
                        argThat(java.util.Map::isEmpty));
        verify(smsCodeService, never()).generateAndStore(any());
    }

    @Test
    void registered_phone_should_generate_code_and_send_Template_A_when_purpose_LOGIN() {
        when(accountRepository.existsByPhone(any(PhoneNumber.class))).thenReturn(true);
        when(smsCodeService.generateAndStore(PHONE)).thenReturn("654321");

        useCaseTemplateCUnavailable().execute(loginCmd());

        verify(smsCodeService).generateAndStore(PHONE);
        verify(smsClient).send(eq(PHONE), eq(RequestSmsCodeUseCase.SMS_TEMPLATE_REGISTER), argThat(m -> "654321"
                .equals(m.get("code"))));
    }

    @Test
    void unregistered_phone_should_send_Template_C_when_purpose_LOGIN_and_template_available() {
        when(accountRepository.existsByPhone(any(PhoneNumber.class))).thenReturn(false);

        useCaseTemplateCAvailable().execute(loginCmd());

        verify(smsClient).send(eq(PHONE), eq(TEMPLATE_C_ID), argThat(java.util.Map::isEmpty));
        verify(smsCodeService, never()).generateAndStore(any());
    }

    @Test
    void unregistered_phone_should_pad_time_when_purpose_LOGIN_and_template_C_unavailable() {
        when(accountRepository.existsByPhone(any(PhoneNumber.class))).thenReturn(false);

        long start = System.nanoTime();
        useCaseTemplateCUnavailable().execute(loginCmd());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // Pad target is 150ms; allow 10ms slack for thread scheduling
        org.assertj.core.api.Assertions.assertThat(elapsedMs).isGreaterThanOrEqualTo(140);

        // No SMS sent, no code generated
        verify(smsClient, never()).send(any(), any(), any());
        verify(smsCodeService, never()).generateAndStore(any());
    }

    @Test
    void should_propagate_60s_rate_limit_block() {
        doThrow(new RateLimitedException("sms-60s:" + PHONE, Duration.ofSeconds(45)))
                .when(rateLimitService)
                .consumeOrThrow(eq("sms-60s:" + PHONE), any());

        assertThatThrownBy(() -> useCaseTemplateCUnavailable().execute(registerCmd()))
                .isInstanceOf(RateLimitedException.class);

        verify(rateLimitService, times(1)).consumeOrThrow(startsWith("sms-"), any());
        verify(accountRepository, never()).existsByPhone(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_propagate_24h_phone_rate_limit_block_after_60s_passes() {
        doThrow(new RateLimitedException("sms-24h:" + PHONE, Duration.ofHours(2)))
                .when(rateLimitService)
                .consumeOrThrow(eq("sms-24h:" + PHONE), any());

        assertThatThrownBy(() -> useCaseTemplateCUnavailable().execute(registerCmd()))
                .isInstanceOf(RateLimitedException.class);

        verify(rateLimitService).consumeOrThrow(eq("sms-60s:" + PHONE), any());
        verify(rateLimitService).consumeOrThrow(eq("sms-24h:" + PHONE), any());
        verify(rateLimitService, never()).consumeOrThrow(startsWith("sms-ip:"), any());
        verify(accountRepository, never()).existsByPhone(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_propagate_IP_rate_limit_block_after_phone_limits_pass() {
        doThrow(new RateLimitedException("sms-ip:" + CLIENT_IP, Duration.ofHours(1)))
                .when(rateLimitService)
                .consumeOrThrow(eq("sms-ip:" + CLIENT_IP), any());

        assertThatThrownBy(() -> useCaseTemplateCUnavailable().execute(registerCmd()))
                .isInstanceOf(RateLimitedException.class);

        verify(rateLimitService).consumeOrThrow(eq("sms-60s:" + PHONE), any());
        verify(rateLimitService).consumeOrThrow(eq("sms-24h:" + PHONE), any());
        verify(rateLimitService).consumeOrThrow(eq("sms-ip:" + CLIENT_IP), any());
        verify(accountRepository, never()).existsByPhone(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_propagate_SmsSendException_from_gateway_failure() {
        when(accountRepository.existsByPhone(any(PhoneNumber.class))).thenReturn(false);
        when(smsCodeService.generateAndStore(PHONE)).thenReturn("123456");
        doThrow(new SmsSendException("aliyun timeout"))
                .when(smsClient)
                .send(eq(PHONE), eq(RequestSmsCodeUseCase.SMS_TEMPLATE_REGISTER), any());

        assertThatThrownBy(() -> useCaseTemplateCUnavailable().execute(registerCmd()))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("aliyun");

        verify(smsCodeService).generateAndStore(PHONE);
    }

    @Test
    void backward_compat_2_arg_command_should_default_to_REGISTER_purpose() {
        when(accountRepository.existsByPhone(any(PhoneNumber.class))).thenReturn(false);
        when(smsCodeService.generateAndStore(PHONE)).thenReturn("888888");

        // Pre-Phase-1.1 callers using the 2-arg overload still work
        useCaseTemplateCUnavailable().execute(new RequestSmsCodeCommand(PHONE, CLIENT_IP));

        verify(smsClient).send(eq(PHONE), eq(RequestSmsCodeUseCase.SMS_TEMPLATE_REGISTER), argThat(m -> "888888"
                .equals(m.get("code"))));
    }
}
