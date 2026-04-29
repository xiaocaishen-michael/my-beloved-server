package com.mbw.app.infrastructure.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.aliyun.tea.TeaException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbw.shared.api.sms.SmsSendException;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Drives {@link AliyunSmsClient} retry behaviour against a Mockito-stubbed SDK (T1b).
 *
 * <p>Verifies that:
 *
 * <ul>
 *   <li>transient failures retry up to maxAttempts then succeed
 *   <li>all-attempts-fail surfaces {@link SmsSendException}
 *   <li>permanent failures fail-fast (no retry)
 * </ul>
 *
 * <p>Backoff is set to 1ms so the test stays sub-second; production
 * uses 200ms-400ms exponential.
 */
class AliyunSmsClientTest {

    private Client sdk;
    private AliyunSmsClient client;

    @BeforeEach
    void setUp() {
        sdk = Mockito.mock(Client.class);
        AliyunSmsProperties props = new AliyunSmsProperties("ak", "secret", "MBW-test", "dysmsapi.aliyuncs.com");
        client = new AliyunSmsClient(sdk, props, new ObjectMapper(), 3, Duration.ofMillis(1), 1.0);
    }

    @Test
    @DisplayName("first attempt succeeds: SDK called exactly once")
    void first_attempt_success_no_retry() throws Exception {
        when(sdk.sendSms(any())).thenReturn(okResponse());

        client.send("+8613800138000", "SMS_123", Map.of("code", "123456"));

        verify(sdk, times(1)).sendSms(any());
    }

    @Test
    @DisplayName("transient TeaException: retries until success")
    void transient_failure_then_success() throws Exception {
        when(sdk.sendSms(any())).thenThrow(throttlingException()).thenReturn(okResponse());

        client.send("+8613800138000", "SMS_123", Map.of("code", "123456"));

        verify(sdk, times(2)).sendSms(any());
    }

    @Test
    @DisplayName("transient body code: retries until success")
    void transient_body_code_then_success() throws Exception {
        when(sdk.sendSms(any())).thenReturn(bodyError("Throttling.User", "qps")).thenReturn(okResponse());

        client.send("+8613800138000", "SMS_123", Map.of("code", "123456"));

        verify(sdk, times(2)).sendSms(any());
    }

    @Test
    @DisplayName("transient IOException: retries cap at maxAttempts then surfaces SmsSendException")
    void transient_io_failure_exceeds_max_attempts() throws Exception {
        when(sdk.sendSms(any())).thenThrow(new RuntimeException(new IOException("connection reset")));

        assertThatThrownBy(() -> client.send("+8613800138000", "SMS_123", Map.of("code", "123456")))
                .isInstanceOf(SmsSendException.class);

        verify(sdk, times(3)).sendSms(any());
    }

    @Test
    @DisplayName("permanent TeaException: no retry, immediate SmsSendException")
    void permanent_failure_fails_fast() throws Exception {
        when(sdk.sendSms(any())).thenThrow(permanentException());

        assertThatThrownBy(() -> client.send("+8613800138000", "SMS_123", Map.of("code", "123456")))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("isv.SMS_SIGNATURE_ILLEGAL");

        verify(sdk, times(1)).sendSms(any());
    }

    @Test
    @DisplayName("permanent body code: no retry, immediate SmsSendException")
    void permanent_body_code_fails_fast() throws Exception {
        when(sdk.sendSms(any())).thenReturn(bodyError("isv.SMS_SIGNATURE_ILLEGAL", "bad sign"));

        assertThatThrownBy(() -> client.send("+8613800138000", "SMS_123", Map.of("code", "123456")))
                .isInstanceOf(SmsSendException.class);

        verify(sdk, times(1)).sendSms(any());
    }

    @Test
    @DisplayName("forwarded params are JSON-serialized into TemplateParam")
    void params_serialized_as_json_in_template_param() throws Exception {
        when(sdk.sendSms(any())).thenReturn(okResponse());
        Map<String, String> params = new HashMap<>();
        params.put("code", "123456");

        client.send("+8613800138000", "SMS_123", params);

        ArgumentCaptor<SendSmsRequest> captor = ArgumentCaptor.forClass(SendSmsRequest.class);
        verify(sdk).sendSms(captor.capture());
        SendSmsRequest sent = captor.getValue();
        assertThat(sent.getPhoneNumbers()).isEqualTo("+8613800138000");
        assertThat(sent.getSignName()).isEqualTo("MBW-test");
        assertThat(sent.getTemplateCode()).isEqualTo("SMS_123");
        assertThat(sent.getTemplateParam()).isEqualTo("{\"code\":\"123456\"}");
    }

    private static SendSmsResponse okResponse() {
        SendSmsResponseBody body = new SendSmsResponseBody().setCode("OK").setMessage("OK");
        return new SendSmsResponse().setBody(body);
    }

    private static SendSmsResponse bodyError(String code, String message) {
        SendSmsResponseBody body = new SendSmsResponseBody().setCode(code).setMessage(message);
        return new SendSmsResponse().setBody(body);
    }

    private static TeaException throttlingException() {
        TeaException ex = new TeaException(Map.of(
                "code", "Throttling.User",
                "message", "qps exceeded"));
        return ex;
    }

    private static TeaException permanentException() {
        TeaException ex = new TeaException(Map.of(
                "code", "isv.SMS_SIGNATURE_ILLEGAL",
                "message", "bad sign"));
        return ex;
    }
}
