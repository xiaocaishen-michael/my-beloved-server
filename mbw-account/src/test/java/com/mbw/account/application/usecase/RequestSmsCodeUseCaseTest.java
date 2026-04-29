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
import com.mbw.account.domain.model.PhoneNumber;
import com.mbw.account.domain.repository.AccountRepository;
import com.mbw.shared.api.sms.SmsClient;
import com.mbw.shared.api.sms.SmsCodeService;
import com.mbw.shared.api.sms.SmsSendException;
import com.mbw.shared.web.RateLimitService;
import com.mbw.shared.web.RateLimitedException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RequestSmsCodeUseCaseTest {

    private static final String PHONE = "+8613800138000";
    private static final String CLIENT_IP = "203.0.113.7";

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private SmsCodeService smsCodeService;

    @Mock
    private SmsClient smsClient;

    @InjectMocks
    private RequestSmsCodeUseCase useCase;

    private RequestSmsCodeCommand cmd;

    @BeforeEach
    void setUp() {
        cmd = new RequestSmsCodeCommand(PHONE, CLIENT_IP);
    }

    @Test
    void unregistered_phone_should_generate_code_and_send_Template_A() {
        when(accountRepository.existsByPhone(any(PhoneNumber.class))).thenReturn(false);
        when(smsCodeService.generateAndStore(PHONE)).thenReturn("123456");

        useCase.execute(cmd);

        verify(rateLimitService).consumeOrThrow(eq("sms-60s:" + PHONE), eq(RequestSmsCodeUseCase.PER_PHONE_60S));
        verify(rateLimitService).consumeOrThrow(eq("sms-24h:" + PHONE), eq(RequestSmsCodeUseCase.PER_PHONE_24H));
        verify(rateLimitService).consumeOrThrow(eq("sms-ip:" + CLIENT_IP), eq(RequestSmsCodeUseCase.PER_IP_24H));
        verify(smsCodeService).generateAndStore(PHONE);
        verify(smsClient).send(eq(PHONE), eq(RequestSmsCodeUseCase.SMS_TEMPLATE_REGISTER), argThat(m -> "123456"
                .equals(m.get("code"))));
    }

    @Test
    void registered_phone_should_send_Template_B_without_code() {
        when(accountRepository.existsByPhone(any(PhoneNumber.class))).thenReturn(true);

        useCase.execute(cmd);

        verify(smsClient)
                .send(
                        eq(PHONE),
                        eq(RequestSmsCodeUseCase.SMS_TEMPLATE_ALREADY_REGISTERED),
                        argThat(java.util.Map::isEmpty));
        verify(smsCodeService, never()).generateAndStore(any());
    }

    @Test
    void should_propagate_60s_rate_limit_block() {
        doThrow(new RateLimitedException("sms-60s:" + PHONE, Duration.ofSeconds(45)))
                .when(rateLimitService)
                .consumeOrThrow(eq("sms-60s:" + PHONE), any());

        assertThatThrownBy(() -> useCase.execute(cmd)).isInstanceOf(RateLimitedException.class);

        verify(rateLimitService, times(1)).consumeOrThrow(startsWith("sms-"), any());
        verify(accountRepository, never()).existsByPhone(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_propagate_24h_phone_rate_limit_block_after_60s_passes() {
        doThrow(new RateLimitedException("sms-24h:" + PHONE, Duration.ofHours(2)))
                .when(rateLimitService)
                .consumeOrThrow(eq("sms-24h:" + PHONE), any());

        assertThatThrownBy(() -> useCase.execute(cmd)).isInstanceOf(RateLimitedException.class);

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

        assertThatThrownBy(() -> useCase.execute(cmd)).isInstanceOf(RateLimitedException.class);

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

        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("aliyun");

        // Code was generated/stored before the SMS gateway failure — that's
        // accepted: the user can retry within the 60s window after the next
        // request gates pass; FR-009 retry happens inside SmsClient impl
        verify(smsCodeService).generateAndStore(PHONE);
    }
}
