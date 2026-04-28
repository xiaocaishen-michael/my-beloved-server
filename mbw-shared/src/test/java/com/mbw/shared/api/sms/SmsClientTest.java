package com.mbw.shared.api.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SmsClientTest {

    @Test
    void should_pass_phone_template_and_params_through_to_implementation() {
        AtomicReference<String> capturedPhone = new AtomicReference<>();
        AtomicReference<String> capturedTemplate = new AtomicReference<>();
        AtomicReference<Map<String, String>> capturedParams = new AtomicReference<>();

        SmsClient client = (phone, templateId, params) -> {
            capturedPhone.set(phone);
            capturedTemplate.set(templateId);
            capturedParams.set(params);
        };

        client.send("+8613800138000", "SMS_TEMPLATE_A", Map.of("code", "123456"));

        assertThat(capturedPhone.get()).isEqualTo("+8613800138000");
        assertThat(capturedTemplate.get()).isEqualTo("SMS_TEMPLATE_A");
        assertThat(capturedParams.get()).containsEntry("code", "123456");
    }

    @Test
    void should_propagate_SmsSendException_from_implementation() {
        SmsClient client = (phone, templateId, params) -> {
            throw new SmsSendException("upstream gateway timeout");
        };

        assertThatThrownBy(() -> client.send("+8613800138000", "SMS_TEMPLATE_A", Map.of()))
                .isInstanceOf(SmsSendException.class)
                .hasMessage("upstream gateway timeout");
    }

    @Test
    void SmsSendException_should_preserve_cause() {
        Throwable rootCause = new IllegalStateException("connection refused");
        SmsSendException ex = new SmsSendException("send failed", rootCause);

        assertThat(ex.getMessage()).isEqualTo("send failed");
        assertThat(ex.getCause()).isSameAs(rootCause);
    }
}
