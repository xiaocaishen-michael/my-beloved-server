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

/**
 * Unit tests for {@link RequestSmsCodeUseCase} (per ADR-0016 unified
 * mobile-first phone-SMS auth simplification — single Template A,
 * no purpose dispatch, no Template B/C branches).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RequestSmsCodeUseCaseTest {

    private static final String PHONE = "+8613800138000";
    private static final String CLIENT_IP = "203.0.113.7";

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private SmsCodeService smsCodeService;

    @Mock
    private SmsClient smsClient;

    private RequestSmsCodeUseCase useCase() {
        return new RequestSmsCodeUseCase(rateLimitService, smsCodeService, smsClient);
    }

    private RequestSmsCodeCommand cmd() {
        return new RequestSmsCodeCommand(PHONE, CLIENT_IP);
    }

    @Test
    void any_phone_should_generate_code_and_send_Template_A_regardless_of_registration_status() {
        // Per FR-004: 反枚举一致响应 — 无论 phone 是否注册都发真 code，
        // client 视角不可区分（unified auth use case 处理已注册 vs 未注册分支）
        when(smsCodeService.generateAndStore(PHONE)).thenReturn("123456");

        useCase().execute(cmd());

        verify(rateLimitService).consumeOrThrow(eq("sms-60s:" + PHONE), eq(RequestSmsCodeUseCase.PER_PHONE_60S));
        verify(rateLimitService).consumeOrThrow(eq("sms-24h:" + PHONE), eq(RequestSmsCodeUseCase.PER_PHONE_24H));
        verify(rateLimitService).consumeOrThrow(eq("sms-ip:" + CLIENT_IP), eq(RequestSmsCodeUseCase.PER_IP_24H));
        verify(smsCodeService).generateAndStore(PHONE);
        verify(smsClient)
                .send(eq(PHONE), eq(RequestSmsCodeUseCase.SMS_TEMPLATE), argThat(m -> "123456".equals(m.get("code"))));
    }

    @Test
    void should_propagate_60s_rate_limit_block() {
        doThrow(new RateLimitedException("sms-60s:" + PHONE, Duration.ofSeconds(45)))
                .when(rateLimitService)
                .consumeOrThrow(eq("sms-60s:" + PHONE), any());

        assertThatThrownBy(() -> useCase().execute(cmd())).isInstanceOf(RateLimitedException.class);

        verify(rateLimitService, times(1)).consumeOrThrow(startsWith("sms-"), any());
        verify(smsCodeService, never()).generateAndStore(any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_propagate_24h_phone_rate_limit_block_after_60s_passes() {
        doThrow(new RateLimitedException("sms-24h:" + PHONE, Duration.ofHours(2)))
                .when(rateLimitService)
                .consumeOrThrow(eq("sms-24h:" + PHONE), any());

        assertThatThrownBy(() -> useCase().execute(cmd())).isInstanceOf(RateLimitedException.class);

        verify(rateLimitService).consumeOrThrow(eq("sms-60s:" + PHONE), any());
        verify(rateLimitService).consumeOrThrow(eq("sms-24h:" + PHONE), any());
        verify(rateLimitService, never()).consumeOrThrow(startsWith("sms-ip:"), any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_propagate_IP_rate_limit_block_after_phone_limits_pass() {
        doThrow(new RateLimitedException("sms-ip:" + CLIENT_IP, Duration.ofHours(1)))
                .when(rateLimitService)
                .consumeOrThrow(eq("sms-ip:" + CLIENT_IP), any());

        assertThatThrownBy(() -> useCase().execute(cmd())).isInstanceOf(RateLimitedException.class);

        verify(rateLimitService).consumeOrThrow(eq("sms-60s:" + PHONE), any());
        verify(rateLimitService).consumeOrThrow(eq("sms-24h:" + PHONE), any());
        verify(rateLimitService).consumeOrThrow(eq("sms-ip:" + CLIENT_IP), any());
        verify(smsClient, never()).send(any(), any(), any());
    }

    @Test
    void should_propagate_SmsSendException_from_gateway_failure() {
        when(smsCodeService.generateAndStore(PHONE)).thenReturn("123456");
        doThrow(new SmsSendException("aliyun timeout"))
                .when(smsClient)
                .send(eq(PHONE), eq(RequestSmsCodeUseCase.SMS_TEMPLATE), any());

        assertThatThrownBy(() -> useCase().execute(cmd()))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("aliyun");

        verify(smsCodeService).generateAndStore(PHONE);
    }
}
