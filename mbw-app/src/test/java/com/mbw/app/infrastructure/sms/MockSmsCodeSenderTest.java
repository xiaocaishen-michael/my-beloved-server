package com.mbw.app.infrastructure.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.mbw.shared.api.email.EmailMessage;
import com.mbw.shared.api.email.EmailSendException;
import com.mbw.shared.api.email.EmailSender;
import com.mbw.shared.api.sms.SmsSendException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Drives {@link MockSmsCodeSender} with a Mockito-stubbed
 * {@link EmailSender} to verify the email mock channel writes the
 * verification code to the configured recipient (ADR-0013 second
 * amendment — channel = Resend HTTPS, abstracted behind EmailSender).
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>EmailMessage from / to / subject / body 内容
 *   <li>EmailSender 失败时抛 {@link SmsSendException}（与真 SMS 失败语义对齐）
 * </ul>
 */
class MockSmsCodeSenderTest {

    private static final MockSmsProperties PROPS =
            new MockSmsProperties("zhangleipd@aliyun.com", "noreply@mail.xiaocaishen.me");

    private EmailSender emailSender;
    private MockSmsCodeSender sender;

    @BeforeEach
    void setUp() {
        emailSender = Mockito.mock(EmailSender.class);
        sender = new MockSmsCodeSender(emailSender, PROPS);
    }

    @Test
    @DisplayName("EmailMessage to / from / subject / text 包含 phone + code")
    void sends_email_with_phone_and_code() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", "123456");

        sender.send("+8613800138000", "SMS_REGISTER_A", params);

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        EmailMessage sent = captor.getValue();

        assertThat(sent.to()).isEqualTo("zhangleipd@aliyun.com");
        assertThat(sent.from()).isEqualTo("noreply@mail.xiaocaishen.me");
        assertThat(sent.subject()).contains("+8613800138000");
        assertThat(sent.text())
                .contains("Phone: +8613800138000")
                .contains("Template: SMS_REGISTER_A")
                .contains("code=123456");
    }

    @Test
    @DisplayName("EmailSender 失败抛 SmsSendException（与真 SMS 失败语义一致）")
    void email_failure_surfaces_as_sms_send_exception() {
        doThrow(new EmailSendException("resend down")).when(emailSender).send(any(EmailMessage.class));

        assertThatThrownBy(() -> sender.send("+8613800138000", "SMS_REGISTER_A", Map.of("code", "123456")))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("Mock SMS send failed");
    }

    @Test
    @DisplayName("同号连续发送时 subject 唯一（绕开 mailbox 反垃圾 dedup）")
    void subject_unique_across_consecutive_sends_to_same_phone() {
        // M1 mock 通道：同号同 code 反复发送（dev 反复测短信流程）时 aliyun /
        // gmail 等收件方反垃圾系统会按 (sender, recipient, subject) 哈希去重，
        // 后续相同 subject 邮件被静默丢弃 — 详见 docs/experience/...（待补）。
        Map<String, String> params = Map.of("code", "111111");

        sender.send("+8613800138000", "SMS_REGISTER_A", params);
        sender.send("+8613800138000", "SMS_REGISTER_A", params);

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, times(2)).send(captor.capture());
        List<EmailMessage> sent = captor.getAllValues();
        assertThat(sent.get(0).subject()).isNotEqualTo(sent.get(1).subject());
    }

    @Test
    @DisplayName("body 中所有 params 都被列出")
    void body_lists_all_params() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", "654321");
        params.put("validity", "5min");
        params.put("appName", "MBW");

        sender.send("+8615900001234", "SMS_LOGIN_B", params);

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());

        String body = captor.getValue().text();
        assertThat(body).contains("code=654321").contains("validity=5min").contains("appName=MBW");
    }
}
