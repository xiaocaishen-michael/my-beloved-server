package com.mbw.app.infrastructure.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mock SMS sender configuration (ADR-0013 — M1 邮件 mock 通道).
 *
 * <p>Bound from {@code mbw.sms.mock.*}. Auto-scanned by
 * {@code @ConfigurationPropertiesScan} on {@code MbwApplication}, so
 * binding happens unconditionally — even when {@code mbw.sms.provider}
 * is unset (dev/test). For that reason fields are <b>not</b>
 * {@code @NotBlank}-validated here; if the values are missing, the
 * gated {@link MockSmsCodeSender} bean is not created at all (and the
 * fallback {@link LoggingSmsClient} steps in). Validation lives in
 * {@code @PostConstruct} on the consuming bean if/when needed.
 *
 * <p>Mirror of the same pattern used by {@code AliyunSmsProperties} —
 * keep dev/test boots clean while still rejecting a misconfigured
 * production startup at the consumer.
 */
@ConfigurationProperties(prefix = "mbw.sms.mock")
public record MockSmsProperties(String recipient, String from) {}
