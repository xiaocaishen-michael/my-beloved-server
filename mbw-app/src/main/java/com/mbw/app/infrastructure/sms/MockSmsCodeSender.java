package com.mbw.app.infrastructure.sms;

import com.mbw.shared.api.email.EmailMessage;
import com.mbw.shared.api.email.EmailSendException;
import com.mbw.shared.api.email.EmailSender;
import com.mbw.shared.api.sms.SmsClient;
import com.mbw.shared.api.sms.SmsSendException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M1 期间的 SMS mock 通道 — 把"短信验证码"发邮件给写死收件人，过渡
 * 到阿里云短信资质审核就绪（详 ADR-0013）。
 *
 * <p>激活条件：{@code mbw.sms.provider=mock}（M1 默认）。生产 ECS
 * 在 {@code .env.app} 里设这个 + {@code mbw.email.provider=resend} +
 * RESEND_API_KEY；切真短信时改成 {@code mbw.sms.provider=aliyun} 即由
 * {@code AliyunSmsClient} 接管，业务代码零改动。
 *
 * <p>实现细节经 ADR-0013 二次 amend（2026-04-30）从 Spring
 * {@code JavaMailSender}（SMTP）切到 {@link EmailSender} 抽象 —
 * production 通道走 Resend HTTPS API，更适合发到 Gmail / Outlook 等
 * 海外邮箱（DirectMail IP 池声誉对 Gmail 投递有硬伤，新约束下不可用）。
 *
 * <p>邮件内容**包含验证码原文**（这是 mock 的目的：让 dev / 演示能拿
 * 到 code 跑 E2E）。这跟 LoggingSmsClient 的 log-safety 原则不冲突 —
 * 邮件写死个人收件人不外发，不会落到生产 stdout / 集中日志。
 *
 * <p>切真 SMS 后**必须删除本类**（详 ADR-0013 § 触发收尾的条件）；
 * EmailSender 抽象与 ResendEmailClient 实现保留，作 M2+ 邮箱注册 /
 * 业务通知发件通道。
 */
@Component
@ConditionalOnProperty(prefix = "mbw.sms", name = "provider", havingValue = "mock")
@EnableConfigurationProperties(MockSmsProperties.class)
public class MockSmsCodeSender implements SmsClient {

    private static final Logger LOG = LoggerFactory.getLogger(MockSmsCodeSender.class);

    private final EmailSender emailSender;
    private final MockSmsProperties properties;

    public MockSmsCodeSender(EmailSender emailSender, MockSmsProperties properties) {
        this.emailSender = emailSender;
        this.properties = properties;
        // Validate at consumer-side rather than @NotBlank on Properties,
        // so dev/test ConfigurationPropertiesScan does not fail boot
        // when these are unset (mock provider not selected).
        requireNonBlank("mbw.sms.mock.recipient", properties.recipient());
        requireNonBlank("mbw.sms.mock.from", properties.from());
    }

    private static void requireNonBlank(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " must be set when mbw.sms.provider=mock (cannot start with blank email)");
        }
    }

    @Override
    public void send(String phone, String templateId, Map<String, String> params) {
        // Subject 必须唯一：aliyun / gmail 等反垃圾按 (sender, recipient, subject) 去重，
        // dev 反复触发同号同 code → 后续邮件静默丢弃。8-char UUID 后缀 cheap 又稳。
        String subject = "[mbw mock SMS] code for " + phone + " ["
                + UUID.randomUUID().toString().substring(0, 8) + "]";
        EmailMessage msg = new EmailMessage(
                properties.from(), properties.recipient(), subject, buildBody(phone, templateId, params));

        try {
            emailSender.send(msg);
        } catch (EmailSendException ex) {
            // 邮件 mock 失败 → 抛 SmsSendException，与真 SMS 失败语义一致；
            // 上层会映射为 HTTP 503 SMS_SEND_FAILED（FR-009）。
            throw new SmsSendException("Mock SMS send failed (email gateway error: " + ex.getMessage() + ")", ex);
        }
        LOG.info(
                "[mock-sms] sent template={} to phone={} (delivered to email={})",
                templateId,
                phone,
                properties.recipient());
    }

    private String buildBody(String phone, String templateId, Map<String, String> params) {
        StringBuilder body = new StringBuilder();
        body.append("Phone: ").append(phone).append('\n');
        body.append("Template: ").append(templateId).append('\n');
        body.append("Params:\n");
        for (Map.Entry<String, String> e : params.entrySet()) {
            body.append("  ")
                    .append(e.getKey())
                    .append("=")
                    .append(e.getValue())
                    .append('\n');
        }
        body.append('\n');
        body.append("(M1 mock channel per ADR-0013; will switch to real SMS once Aliyun signature is approved.)");
        return body.toString();
    }
}
