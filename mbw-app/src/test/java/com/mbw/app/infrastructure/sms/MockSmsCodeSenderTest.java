package com.mbw.app.infrastructure.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.mbw.shared.api.sms.SmsSendException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Drives {@link MockSmsCodeSender} with a Mockito-stubbed
 * {@link JavaMailSender} to verify the email mock channel writes the
 * verification code to the configured recipient (ADR-0013).
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>邮件 to / from / subject / body 内容
 *   <li>SMTP 失败时抛 {@link SmsSendException}（与真 SMS 失败语义对齐）
 * </ul>
 */
class MockSmsCodeSenderTest {

    private static final MockSmsProperties PROPS =
            new MockSmsProperties("zhangleipd@aliyun.com", "noreply@example.com");

    private JavaMailSender mailSender;
    private MockSmsCodeSender sender;

    @BeforeEach
    void setUp() {
        mailSender = Mockito.mock(JavaMailSender.class);
        sender = new MockSmsCodeSender(mailSender, PROPS);
    }

    @Test
    @DisplayName("邮件 to / from / subject / body 包含 phone + code")
    void sends_email_with_phone_and_code() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", "123456");

        sender.send("+8613800138000", "SMS_REGISTER_A", params);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();

        assertThat(sent.getTo()).containsExactly("zhangleipd@aliyun.com");
        assertThat(sent.getFrom()).isEqualTo("noreply@example.com");
        assertThat(sent.getSubject()).contains("+8613800138000");
        assertThat(sent.getText())
                .contains("Phone: +8613800138000")
                .contains("Template: SMS_REGISTER_A")
                .contains("code=123456");
    }

    @Test
    @DisplayName("SMTP 失败抛 SmsSendException（与真 SMS 失败语义一致）")
    void smtp_failure_surfaces_as_sms_send_exception() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.send("+8613800138000", "SMS_REGISTER_A", Map.of("code", "123456")))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("Mock SMS send failed");
    }

    @Test
    @DisplayName("body 中所有 params 都被列出")
    void body_lists_all_params() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", "654321");
        params.put("validity", "5min");
        params.put("appName", "MBW");

        sender.send("+8615900001234", "SMS_LOGIN_B", params);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        String body = captor.getValue().getText();
        assertThat(body).contains("code=654321").contains("validity=5min").contains("appName=MBW");
    }
}
